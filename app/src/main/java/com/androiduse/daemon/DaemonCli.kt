package com.androiduse.daemon

import com.androiduse.root.DaemonClient

/**
 * 仅供真机端到端联调：用 App 的 APK 作 classpath 起一个客户端进程，走真正的
 * [DaemonClient.dump] 路径（含"连不上就拉起守护进程"），把结果打到 stdout。
 *
 * 用法（adb）：
 *   su root env CLASSPATH=<apk> app_process /system/bin \
 *       com.androiduse.daemon.DaemonCli <apkPath> dump <displayId> | create | frame [out] | destroy | lease <s>
 *
 * 不进 App 正常运行路径，只是联调工具。
 */
object DaemonCli {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            println("usage: DaemonCli <apkPath> dump <displayId> | create | frame [out.jpg] | destroy | lease <seconds>")
            return
        }
        DaemonClient.apkPath = args[0]
        when (args[1]) {
            "dump" -> {
                val displayId = args.getOrNull(2)?.toIntOrNull() ?: run { println("bad displayId"); return }
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
            }
            "create" -> println(DaemonClient.rawRequest(DaemonProtocol.encodeCreateDisplay(1080, 2376, 480)))
            "destroy" -> println(DaemonClient.rawRequest(DaemonProtocol.encodeDestroyDisplay()))
            "frame" -> {
                val line = DaemonClient.rawRequest(DaemonProtocol.encodeFrame()) ?: run { println("no response"); return }
                when (val f = DaemonProtocol.parseFrameResponse(line)) {
                    is DaemonProtocol.FrameResult.Ok -> {
                        val bytes = android.util.Base64.decode(f.jpegBase64, android.util.Base64.NO_WRAP)
                        val out = args.getOrNull(2) ?: "/data/local/tmp/daemoncli_frame.jpg"
                        java.io.File(out).writeBytes(bytes)
                        println("FRAME ${bytes.size} bytes -> $out")
                    }
                    DaemonProtocol.FrameResult.Empty -> println("EMPTY (no frame yet)")
                    is DaemonProtocol.FrameResult.Err -> println("ERR ${f.message}")
                }
            }
            "lease" -> {
                val secs = args.getOrNull(2)?.toIntOrNull() ?: 10
                val s = DaemonClient.openRawSocket() ?: run { println("cannot connect"); return }
                s.outputStream.write((DaemonProtocol.encodeLease() + "\n").toByteArray())
                s.outputStream.flush()
                println("lease reply: " + s.inputStream.bufferedReader().readLine())
                println("holding lease ${secs}s then exiting (daemon should destroy display)")
                Thread.sleep(secs * 1000L)
            }
            else -> println("unknown subcommand ${args[1]}")
        }
        System.exit(0)
    }
}
