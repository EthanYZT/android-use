package com.androiduse.daemon

import com.androiduse.root.DaemonClient

/**
 * 仅供真机端到端联调：用 App 的 APK 作 classpath 起一个客户端进程，走真正的
 * [DaemonClient.dump] 路径（含"连不上就拉起守护进程"），把结果打到 stdout。
 *
 * 用法（adb）：
 *   su root env CLASSPATH=<apk> app_process /system/bin \
 *       com.androiduse.daemon.DaemonCli <apkPath> <displayId>
 *
 * 不进 App 正常运行路径，只是联调工具。
 */
object DaemonCli {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("usage: DaemonCli <apkPath> <displayId>")
            return
        }
        DaemonClient.apkPath = args[0]
        val displayId = args[1].toIntOrNull() ?: run { println("bad displayId"); return }
        when (val r = DaemonClient.dump(displayId)) {
            is DumpCodec.DumpResult.Ok -> {
                println("OK display=${r.displayId} nodes=${r.nodes.size}")
                r.nodes.take(40).forEach { n ->
                    println("  #${n.id} [${n.left},${n.top},${n.right},${n.bottom}]" +
                        (if (n.clickable) " CLICK" else "") +
                        (if (n.resId.isNotEmpty()) " id=${n.resId}" else "") +
                        (if (n.text.isNotEmpty()) " text=\"${n.text}\"" else "") +
                        (if (n.desc.isNotEmpty()) " desc=\"${n.desc}\"" else ""))
                }
            }
            is DumpCodec.DumpResult.Err -> println("ERR ${r.message}")
        }
        System.exit(0)
    }
}
