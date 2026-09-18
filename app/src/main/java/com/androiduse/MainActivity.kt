package com.androiduse

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.androiduse.agent.AgentLoop
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)


        binding.rvSteps.layoutManager = LinearLayoutManager(this)
        binding.rvSteps.adapter = adapter
        binding.btnRun.setOnClickListener { startTask() }
        binding.btnStop.setOnClickListener { job?.cancel() }
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

        job = lifecycleScope.launch {
            setRunning(true)
            adapter.submit(emptyList())
            binding.tvResult.visibility = View.GONE
            try {
                binding.toolbar.subtitle = getString(R.string.status_preparing_screen)
                val screen = withContext(Dispatchers.IO) { ScreenSession.ensure() }
                updateScreenStatus()
                if (screen == null) {
                    showResult(false, getString(R.string.error_screen_create_failed))
                    return@launch
                }

                val store = TranscriptStore(File(filesDir, "transcripts"))
                val sink = object : TranscriptSink {
                    override fun start(t: Transcript) = store.start(t)
                    override fun saveScreenshot(t: Transcript, stepIndex: Int, jpegBase64: String) =
                        store.saveScreenshot(t, stepIndex, jpegBase64)
                    override fun step(t: Transcript, step: Step) {
                        store.step(t, step)
                        val stored = step.toStored()
                        runOnUiThread {
                            adapter.add(stored)
                            binding.rvSteps.scrollToPosition(adapter.itemCount - 1)
                        }
                    }
                    override fun outcome(t: Transcript, finished: Boolean, summary: String) =
                        store.outcome(t, finished, summary)
                }
                val client = ArkChatClient(BuildConfig.ARK_API_KEY, BuildConfig.ARK_BASE_URL)
                val outcome = AgentLoop(client, AndroidEnvironment(screen, packageManager), BuildConfig.ARK_MODEL_ID, sink)
                    .run(task) { line ->
                        Log.i("AgentLoop", line) // 镜像到 logcat，便于 adb 联调
                        runOnUiThread { binding.toolbar.subtitle = line.take(90) }
                    }
                showResult(outcome.finished, outcome.summary)
            } catch (e: CancellationException) {
                showResult(false, getString(R.string.result_stopped))
            } finally {
                setRunning(false)
            }
        }
    }

    private fun setRunning(running: Boolean) {
        binding.btnRun.isEnabled = !running
        binding.etTask.isEnabled = !running
        binding.btnStop.visibility = if (running) View.VISIBLE else View.GONE
        binding.progress.visibility = if (running) View.VISIBLE else View.GONE
        if (!running) binding.toolbar.subtitle = null
    }

    private fun showResult(finished: Boolean, summary: String) {
        binding.tvResult.text = (if (finished) getString(R.string.result_finished) else getString(R.string.result_unfinished)) + "\n" + summary
        binding.tvResult.visibility = View.VISIBLE
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
