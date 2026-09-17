package com.androiduse

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.androiduse.BuildConfig
import com.androiduse.agent.AgentLoop
import com.androiduse.agent.ArkChatClient
import com.androiduse.root.DaemonClient
import com.androiduse.databinding.ActivityMainBinding
import com.androiduse.display.VirtualDisplayManager
import com.androiduse.display.VirtualScreen
import com.androiduse.log.TranscriptStore
import java.io.File
import com.androiduse.perception.ScreenCapture
import com.androiduse.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentScreen: VirtualScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ScreenCapture.cacheDir = cacheDir
        // 守护进程复用本 App 的 APK 作 classpath（见 DaemonClient）。sourceDir 是 App 自控路径。
        DaemonClient.apkPath = applicationInfo.sourceDir

        binding.tvLog.movementMethod = ScrollingMovementMethod()

        binding.btnCheckRoot.setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { RootShell.exec("id") }
                appendLog("exit=${result.exitCode}\n${result.stdout}${result.stderr}")
            }
        }

        binding.btnCreateScreen.setOnClickListener {
            lifecycleScope.launch {
                setScreenButtonsEnabled(false)
                try {
                    val screen = withContext(Dispatchers.IO) { VirtualDisplayManager.create() }
                    if (screen == null) {
                        appendLog("建屏失败")
                    } else {
                        val launched = withContext(Dispatchers.IO) {
                            VirtualDisplayManager.launchIntentAction("android.settings.SETTINGS", screen)
                        }
                        currentScreen = screen
                        appendLog(
                            "建屏成功 logicalId=${screen.logicalDisplayId} sfId=${screen.surfaceFlingerId.toULong()} " +
                                "启动=${if (launched) "成功" else "失败"}"
                        )
                    }
                } finally {
                    setScreenButtonsEnabled(true)
                }
            }
        }

        binding.btnDestroyScreen.setOnClickListener {
            lifecycleScope.launch {
                setScreenButtonsEnabled(false)
                try {
                    val destroyed = withContext(Dispatchers.IO) { VirtualDisplayManager.destroy() }
                    if (destroyed) currentScreen = null
                    appendLog(if (destroyed) "已销毁虚拟屏" else "销毁虚拟屏失败")
                } finally {
                    setScreenButtonsEnabled(true)
                }
            }
        }

        binding.btnCapture.setOnClickListener {
            val screen = currentScreen
            if (screen == null) {
                appendLog("还没建屏")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                setScreenButtonsEnabled(false)
                try {
                    val t0 = System.currentTimeMillis()
                    val bmp = withContext(Dispatchers.IO) { ScreenCapture.capture(screen) }
                    val cost = System.currentTimeMillis() - t0
                    if (bmp == null) {
                        appendLog("截图失败")
                    } else {
                        binding.ivPreview.setImageBitmap(bmp)
                        appendLog("截图成功 ${bmp.width}x${bmp.height} 耗时 ${cost}ms")
                    }
                } finally {
                    setScreenButtonsEnabled(true)
                }
            }
        }

        binding.btnRunTask.setOnClickListener {
            val screen = currentScreen
            if (screen == null) { appendLog("请先建虚拟屏"); return@setOnClickListener }
            val task = binding.etTask.text.toString().trim()
            if (task.isEmpty()) { appendLog("请输入任务"); return@setOnClickListener }

            lifecycleScope.launch {
                // 和建屏/销屏/截图一样禁用按钮：btnRunTask 自己也在列表里，防止重复点击
                // 开出第二个并发的 AgentLoop（两个循环的日志会交错写进同一个 tvLog，
                // Injector.perform 之间也没有互斥）；btnDestroyScreen 也被禁用，防止
                // 任务跑到一半时虚拟屏被销毁，循环继续对着已经不存在的 logicalDisplayId
                // 发命令。
                setScreenButtonsEnabled(false)
                try {
                    appendLog("=== 开始任务: $task ===")
                    val client = ArkChatClient(BuildConfig.ARK_API_KEY, BuildConfig.ARK_BASE_URL)
                    val store = TranscriptStore(File(filesDir, "transcripts"))
                    val outcome = AgentLoop(client, AndroidEnvironment(screen), BuildConfig.ARK_MODEL_ID, store)
                        .run(task) { line ->
                            android.util.Log.i("AgentLoop", line) // 镜像到 logcat，便于 adb 联调
                            runOnUiThread { appendLog(line) }
                        }
                    appendLog("=== ${if (outcome.finished) "任务完成: " else ""}${outcome.summary} ===")
                    appendLog("日志: ${File(filesDir, "transcripts/${outcome.transcript.taskId}").absolutePath}")
                } finally {
                    setScreenButtonsEnabled(true)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // F-2：overlay_display_devices 是跨进程、**跨重启**持久化的全局 setting（见
        // VirtualDisplayManager 顶部注释）。这个 Activity 是虚拟屏的唯一创建者，如果它销毁
        // 时不清理，用户划掉 App 或系统杀掉进程后，这块叠加窗口会作为可见的悬浮窗永远留在
        // 物理屏上，直到手动执行 destroy 或重启手机——而重启后设置还在，悬浮窗会再出现。
        //
        // 只在 isFinishing 为 true（Activity 真正结束）时清理，配置变更（例如旋转屏幕）触发
        // 的销毁-重建不算：那种情况 isFinishing 是 false，马上会有一个新的 Activity 实例
        // 接手同一个 currentScreen 继续用，销毁虚拟屏反而会把它拆掉。
        //
        // 这里同步调用而不是丢进 lifecycleScope：Activity 销毁时 lifecycleScope 已经被取消，
        // 协程可能一步都不跑就被丢弃，清理无法保证发生；VirtualDisplayManager.destroy() 只是
        // 几条快速的 settings 读写命令（不是 RootShell 默认 15s 超时的重活），同步跑完可接受。
        if (isFinishing) {
            VirtualDisplayManager.destroy()
            currentScreen = null
        }
    }

    /** 建屏/销屏/截图/执行任务操作进行中禁用相关按钮，避免用户并发点击触发重叠调用。 */
    private fun setScreenButtonsEnabled(enabled: Boolean) {
        binding.btnCreateScreen.isEnabled = enabled
        binding.btnDestroyScreen.isEnabled = enabled
        binding.btnCapture.isEnabled = enabled
        binding.btnRunTask.isEnabled = enabled
    }

    private fun appendLog(line: String) {
        binding.tvLog.append(line + "\n")
    }
}
