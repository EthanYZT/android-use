package com.androiduse.daemon

import com.androiduse.BuildConfig
import com.androiduse.agent.AgentLoop
import com.androiduse.agent.ArkChatClient
import com.androiduse.AndroidEnvironment
import com.androiduse.display.VirtualDisplayManager
import com.androiduse.log.TranscriptStore
import com.androiduse.perception.ScreenCapture
import com.androiduse.root.DaemonClient
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 仅供真机端到端联调：无 UI 直接跑一遍 [AgentLoop]（建虚拟屏 → 启动设置 → 节点 grounding 循环）。
 * 绕开 App UI 驱动和 `input text` 不支持中文的问题——任务作为 argv 传进来。
 *
 * 用法：su root env CLASSPATH=<apk> app_process /system/bin \
 *          com.androiduse.daemon.AgentCli <apkPath> <maxSteps> <task...>
 */
object AgentCli {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) { println("usage: AgentCli <apkPath> <maxSteps> <task...>"); return }
        val apkPath = args[0]
        val maxSteps = args[1].toIntOrNull() ?: 6
        val task = args.drop(2).joinToString(" ")
        // ActivityThread 构造需要当前线程有 Looper（与 Daemon 的要点 1 同源）。
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val pm = systemPackageManager()
        println("packageManager=${if (pm == null) "不可用(open_app 关闭)" else "ok"}")

        DaemonClient.apkPath = apkPath
        ScreenCapture.cacheDir = File("/data/local/tmp") // CLI 以 root 跑，可读写

        val screen = VirtualDisplayManager.create()
        if (screen == null) { println("建屏失败"); return }
        println("screen logicalId=${screen.logicalDisplayId} ${screen.widthPx}x${screen.heightPx}")
        VirtualDisplayManager.launchIntentAction("android.settings.SETTINGS", screen)
        Thread.sleep(2500)

        val client = ArkChatClient(BuildConfig.ARK_API_KEY, BuildConfig.ARK_BASE_URL)
        val store = TranscriptStore(File("/data/local/tmp/androiduse_transcripts"))
        try {
            val outcome = runBlocking {
                AgentLoop(client, AndroidEnvironment(screen, pm), BuildConfig.ARK_MODEL_ID, store)
                    .run(task, maxSteps = maxSteps) { println(it) }
            }
            println("RESULT: finished=${outcome.finished} ${outcome.summary}")
            println("TRANSCRIPT: /data/local/tmp/androiduse_transcripts/${outcome.transcript.taskId}/transcript.jsonl")
        } finally {
            VirtualDisplayManager.destroy()
        }
        System.exit(0)
    }

    /**
     * 裸 app_process 里没有应用 Context，用 `ActivityThread.systemMain().getSystemContext()` 拿
     * 系统 Context 的 PackageManager 来查桌面 App 列表。hidden API，与守护进程的
     * UiAutomationFactory 同一类做法；失败只关掉 open_app，不影响其余循环。
     */
    private fun systemPackageManager(): PackageManager? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        val ctx = at.getMethod("getSystemContext").invoke(thread) as Context
        ctx.packageManager
    } catch (e: Throwable) {
        println("systemMain 取 PackageManager 失败: $e")
        null
    }
}
