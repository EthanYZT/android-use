package com.androiduse.daemon

import com.androiduse.BuildConfig
import com.androiduse.agent.AgentLoop
import com.androiduse.agent.ArkVisionClient
import com.androiduse.display.VirtualDisplayManager
import com.androiduse.log.TaskLogger
import com.androiduse.perception.ScreenCapture
import com.androiduse.root.DaemonClient
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

        DaemonClient.apkPath = apkPath
        ScreenCapture.cacheDir = File("/data/local/tmp") // CLI 以 root 跑，可读写

        val screen = VirtualDisplayManager.create()
        if (screen == null) { println("建屏失败"); return }
        println("screen logicalId=${screen.logicalDisplayId} ${screen.widthPx}x${screen.heightPx}")
        VirtualDisplayManager.launchIntentAction("android.settings.SETTINGS", screen)
        Thread.sleep(2500)

        val client = ArkVisionClient(BuildConfig.ARK_API_KEY, BuildConfig.ARK_BASE_URL, BuildConfig.ARK_MODEL_ID)
        try {
            val result = runBlocking {
                AgentLoop(client, TaskLogger()).run(task, screen, maxSteps = maxSteps) { println(it) }
            }
            println("RESULT: $result")
        } finally {
            VirtualDisplayManager.destroy()
        }
        System.exit(0)
    }
}
