package com.androiduse

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.androiduse.agent.AgentLoop
import com.androiduse.perception.MlKitTextReader
import com.androiduse.agent.ArkChatClient
import com.androiduse.agent.Step
import com.androiduse.agent.Transcript
import com.androiduse.agent.TranscriptSink
import com.androiduse.agent.toStored
import com.androiduse.databinding.ActivityMainBinding
import com.androiduse.log.TranscriptStore
import com.androiduse.ui.StepAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主屏只做一件事：输入任务、执行、实时看每步的笔记与动作。
 * 虚拟屏由 [ScreenSession] 自动处理；调试按钮在 [SettingsActivity]；历史在 [HistoryActivity]。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = StepAdapter()
    private var job: Job? = null

    companion object { const val EXTRA_TASK = "task" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)


        binding.rvSteps.layoutManager = LinearLayoutManager(this)
        binding.rvSteps.adapter = adapter
        binding.btnRun.setOnClickListener { startTask() }
        binding.btnStop.setOnClickListener { job?.cancel() }
        binding.btnTakeover.setOnClickListener { takeover() }
        handleTaskExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTaskExtra(intent)
    }

    /**
     * 调试入口（spec 1d §4.3）：`am start -n com.androiduse/.MainActivity --es task "<任务>"`（root 可起，
     * Activity 未 exported）。收到 extra 自动填入并执行，让 adb 能驱动 App 进程跑任务（OCR 只在 App 进程可用）。
     */
    private fun handleTaskExtra(intent: Intent?) {
        val task = intent?.getStringExtra(EXTRA_TASK)?.trim().orEmpty()
        if (task.isEmpty()) return
        intent?.removeExtra(EXTRA_TASK)
        binding.etTask.setText(task)
        if (job?.isActive != true) startTask()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_history -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        updateScreenStatus()
    }

    private fun startTask() {
        val task = binding.etTask.text?.toString()?.trim().orEmpty()
        if (task.isEmpty()) {
            binding.tilTask.error = getString(R.string.error_task_empty)
            return
        }
        binding.tilTask.error = null

        // 前台服务 + 通知：否则切后台 5 秒即被 HANS 冻结断网（见 AgentForegroundService）。通知权限没给也照样起服务，只是不显示。
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        AgentForegroundService.start(this, task)
        job = lifecycleScope.launch {
            setRunning(true)
            // Doze 白名单：ColorOS 的 HANS 只认这个，前台服务单独不够（见 BackgroundExemption）。root 一次性自授。
            withContext(Dispatchers.IO) { com.androiduse.power.BackgroundExemption.ensure(applicationContext) }
            adapter.submit(emptyList())
            binding.tvResult.visibility = View.GONE
            try {
                binding.toolbar.subtitle = getString(R.string.status_preparing_screen)
                val screen = withContext(Dispatchers.IO) { ScreenSession.ensure() }
                updateScreenStatus()
                if (screen == null) {
                    showError(getString(R.string.error_screen_create_failed))
                    return@launch
                }

                val store = TranscriptStore(File(filesDir, "transcripts"))
                val sink = object : TranscriptSink {
                    override fun start(t: Transcript) = store.start(t)
                    override fun saveScreenshot(t: Transcript, stepIndex: Int, jpegBase64: String) =
                        store.saveScreenshot(t, stepIndex, jpegBase64)
                    override fun saveNodes(t: Transcript, stepIndex: Int, nodes: List<com.androiduse.daemon.DumpCodec.NodeRecord>, ocrLines: List<com.androiduse.agent.OcrLine>?) =
                        store.saveNodes(t, stepIndex, nodes, ocrLines)
                    override fun step(t: Transcript, step: Step) {
                        store.step(t, step)
                        val stored = step.toStored()
                        runOnUiThread {
                            adapter.add(stored)
                            binding.rvSteps.scrollToPosition(adapter.itemCount - 1)
                        }
                    }
                    override fun outcome(t: Transcript, finished: Boolean, summary: String, handoff: Boolean) =
                        store.outcome(t, finished, summary, handoff)
                }
                val client = ArkChatClient(BuildConfig.ARK_API_KEY, BuildConfig.ARK_BASE_URL)
                val outcome = AgentLoop(client, AndroidEnvironment(screen, applicationContext, MlKitTextReader), BuildConfig.ARK_MODEL_ID, sink)
                    .run(task, maxSteps = AgentLoop.UNLIMITED_STEPS) { line ->
                        Log.i("AgentLoop", line) // 镜像到 logcat，便于 adb 联调
                        runOnUiThread { binding.toolbar.subtitle = line.take(90) }
                    }
                showResult(outcome)
            } catch (e: CancellationException) {
                showResult(null)
            } finally {
                setRunning(false)
                AgentForegroundService.stop(this@MainActivity)
            }
        }
    }

    private fun setRunning(running: Boolean) {
        binding.btnRun.isEnabled = !running
        binding.etTask.isEnabled = !running
        binding.btnStop.visibility = if (running) View.VISIBLE else View.GONE
        binding.progress.visibility = if (running) View.VISIBLE else View.GONE
        // 任务一开跑，虚拟屏上的旧结果不再代表当前状态：先把接管按钮收起来，避免跟正在跑的新任务打架（见 takeover() 的守卫）。
        if (running) binding.btnTakeover.visibility = View.GONE
        if (!running) binding.toolbar.subtitle = null
    }

    /** 建虚拟屏失败等无法继续的错误：只显示文本，不出"在手机上继续"按钮（没有虚拟屏可接管）。 */
    private fun showError(text: String) {
        binding.tvResult.text = text
        binding.tvResult.visibility = View.VISIBLE
    }

    private fun showResult(outcome: AgentLoop.Outcome?) {
        val label = when {
            outcome == null -> getString(R.string.result_stopped)
            outcome.handoff -> getString(R.string.result_handoff) + "：" + outcome.summary
            outcome.finished -> getString(R.string.result_finished) + "\n" + outcome.summary
            else -> getString(R.string.result_unfinished) + "\n" + outcome.summary
        }
        binding.tvResult.text = label
        binding.tvResult.visibility = View.VISIBLE
        // 这个查询是脱离 job 的独立协程（IO 耗时），期间用户可能已经点了停止或开了新任务——
        // job 字段会变成别的 Job、或还是这个 Job 但已经在跑。只有"还是当初那个 job 且它已经结束"才把结果应用到按钮上。
        val myJob = job
        lifecycleScope.launch {
            val show = withContext(Dispatchers.IO) { ScreenHandover.hasTakeoverTarget() }
            if (job === myJob && job?.isActive != true) {
                binding.btnTakeover.visibility = if (show) View.VISIBLE else View.GONE
                binding.btnTakeover.isEnabled = true
            }
        }
    }

    private fun takeover() {
        if (job?.isActive == true) return // 任务还在跑就不能接管；按钮此时其实也应该是隐藏的（setRunning），这里再兜底一层。
        binding.btnTakeover.isEnabled = false
        binding.toolbar.subtitle = getString(R.string.status_taking_over)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { ScreenHandover.takeover() }
            binding.toolbar.subtitle = null
            updateScreenStatus()
            r.onSuccess {
                binding.btnTakeover.visibility = View.GONE
                moveTaskToBack(true)
            }.onFailure { e ->
                binding.btnTakeover.isEnabled = true
                Toast.makeText(this@MainActivity, e.message ?: "切换失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateScreenStatus() {
        val s = ScreenSession.screen
        binding.tvScreenStatus.text =
            if (s == null) getString(R.string.screen_status_none) else getString(R.string.screen_status_ready, s.logicalDisplayId)
    }

    override fun onDestroy() {
        super.onDestroy()
        // F-2：overlay_display_devices 是跨进程、跨重启持久化的全局 setting。只在 Activity 真正
        // 结束时清理（配置变更的销毁-重建 isFinishing 为 false，不清理）。同步调用：此时
        // lifecycleScope 已取消，协程可能一步都不跑。
        if (isFinishing) ScreenSession.destroy()
    }
}
