package com.androiduse.daemon

import com.androiduse.BuildConfig
import com.androiduse.agent.AgentLoop
import com.androiduse.agent.ArkChatClient
import com.androiduse.AndroidEnvironment
import com.androiduse.display.ScreenSessionCore
import com.androiduse.log.TranscriptStore
import com.androiduse.root.DaemonClient
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 仅供真机端到端联调：无 UI 直接跑一遍 [AgentLoop]（守护进程建不可见虚拟屏 → 启动设置 → agent 循环）。
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
        val maxSteps = args[1].toIntOrNull() ?: 15
        val task = args.drop(2).joinToString(" ")
        // ActivityThread 构造需要当前线程有 Looper（与 Daemon 的要点 1 同源）。
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val ctx = systemContext()
        println("systemContext=${if (ctx == null) "不可用(open_app/系统接口关闭)" else "ok"}")

        DaemonClient.apkPath = apkPath

        // 与 App 同一条路：租约 → 守护进程建不可见屏 → 起设置。CLI 进程退出即租约 EOF，守护进程自行销屏。
        val session = ScreenSessionCore(DaemonClient)
        val screen = session.ensure()
        if (screen == null) { println("建屏失败（守护进程不可达或 create_display 失败）"); return }
        println("screen logicalId=${screen.logicalDisplayId} ${screen.widthPx}x${screen.heightPx} (headless)")

        val client = ArkChatClient(BuildConfig.ARK_API_KEY, BuildConfig.ARK_BASE_URL)
        val store = TranscriptStore(File("/data/local/tmp/androiduse_transcripts"))
        try {
            val outcome = runBlocking {
                // systemMain 的系统 Context 调 Provider 会被 SecurityException 拒绝（不是权限问题，pm grant 救不了）；
                // providersAvailable=false 让日历/联系人工具直接给出明确失败文案，不去碰 ContentResolver。
                AgentLoop(client, AndroidEnvironment(screen, ctx, providersAvailable = false), BuildConfig.ARK_MODEL_ID, store)
                    .run(task, maxSteps = maxSteps) { println(it) }
            }
            println("RESULT: finished=${outcome.finished} ${outcome.summary}")
            println("TRANSCRIPT: /data/local/tmp/androiduse_transcripts/${outcome.transcript.taskId}/transcript.jsonl")
        } finally {
            session.destroy()
        }
        System.exit(0)
    }

    /**
     * 裸 app_process 里没有应用 Context，用 `ActivityThread.systemMain().getSystemContext()` 拿
     * 系统 Context（供 open_app 的 PackageManager、2a 系统接口工具的 SystemInterfaces 用）。
     * hidden API，与守护进程的 UiAutomationFactory 同一类做法；失败只关掉 open_app/系统接口，
     * 不影响其余循环。
     */
    private fun systemContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    } catch (e: Throwable) {
        println("systemMain 取 Context 失败: $e")
        null
    }
}
