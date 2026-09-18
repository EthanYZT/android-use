package com.androiduse.root

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.androiduse.daemon.Daemon
import com.androiduse.daemon.DaemonProtocol
import com.androiduse.daemon.DumpCodec
import com.androiduse.display.DisplayService
import java.io.Closeable
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * App 侧的守护进程客户端：确保守护进程在跑，并通过 LocalSocket 发 dump 请求、拿节点树。
 *
 * 守护进程复用 **App 自己的 APK** 作为 classpath（[apkPath]，即 applicationInfo.sourceDir），
 * 用 `app_process` 起 [com.androiduse.daemon.Daemon]——不单独出 dex，Daemon 类就在 app 模块里。
 *
 * 透明重启：连不上就 [ensureStarted] 拉起再重试（守护进程空闲 60s 自杀，见 Daemon）。
 * 全部是阻塞 IO，调用方应放在 IO 线程（AgentLoop 已在 Dispatchers.IO）。
 */
object DaemonClient : DisplayService {

    private const val TAG = "DaemonClient"

    /** App 的 APK 路径（sourceDir）。由初始化时注入（MainActivity / CLI 入口）。 */
    lateinit var apkPath: String

    /** dump 指定逻辑屏的节点树。失败返回 [DumpCodec.DumpResult.Err]。 */
    fun dump(displayId: Int): DumpCodec.DumpResult {
        val socket = openRawSocket() ?: return DumpCodec.DumpResult.Err("daemon unreachable")

        return socket.use { s ->
            try {
                val out = s.outputStream
                out.write((DumpCodec.encodeRequest(DumpCodec.DumpRequest(displayId)) + "\n")
                    .toByteArray(StandardCharsets.UTF_8))
                out.flush()
                val reader = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8))
                val line = reader.readLine() ?: return@use DumpCodec.DumpResult.Err("empty response")
                DumpCodec.parseResponse(line)
            } catch (e: Exception) {
                DumpCodec.DumpResult.Err("io error: ${e.message}")
            }
        }
    }

    /** 连上（必要时拉起）守护进程并返回裸 socket；连不上返回 null。先直接试连，连不上再拉起、退避重试。 */
    fun openRawSocket(): LocalSocket? {
        tryConnect()?.let { return it }
        Log.i(TAG, "daemon not up, launching")
        ensureStarted()
        return connectWithBackoff()
    }

    /** 发一行、收一行、关连接。连不上/IO 失败返回 null。 */
    fun rawRequest(line: String): String? {
        val s = openRawSocket() ?: return null
        return s.use {
            try {
                it.outputStream.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
                it.outputStream.flush()
                BufferedReader(InputStreamReader(it.inputStream, StandardCharsets.UTF_8)).readLine()
            } catch (e: Exception) {
                null
            }
        }
    }

    // ---- DisplayService（v2）----

    override fun openLease(): Closeable? {
        val s = openRawSocket() ?: return null
        return try {
            s.outputStream.write((DaemonProtocol.encodeLease() + "\n").toByteArray(StandardCharsets.UTF_8))
            s.outputStream.flush()
            val line = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8)).readLine()
            if (line != null && DaemonProtocol.parseSimpleResponse(line) == null) {
                // 租约 socket 上不能再发请求；句柄只负责 close（守护进程读到 EOF 即销屏）。
                Closeable { try { s.close() } catch (_: Exception) {} }
            } else {
                s.close(); null
            }
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            null
        }
    }

    override fun createDisplay(w: Int, h: Int, dpi: Int): DaemonProtocol.CreateResult {
        val line = rawRequest(DaemonProtocol.encodeCreateDisplay(w, h, dpi))
            ?: return DaemonProtocol.CreateResult.Err("daemon unreachable")
        return DaemonProtocol.parseCreateResponse(line)
    }

    override fun destroyDisplay(): Boolean {
        val line = rawRequest(DaemonProtocol.encodeDestroyDisplay()) ?: return false
        return DaemonProtocol.parseSimpleResponse(line) == null
    }

    override fun frame(maxWidth: Int, quality: Int): DaemonProtocol.FrameResult {
        val line = rawRequest(DaemonProtocol.encodeFrame(maxWidth, quality))
            ?: return DaemonProtocol.FrameResult.Err("daemon unreachable")
        return DaemonProtocol.parseFrameResponse(line)
    }

    /**
     * `-f 0x18000000`（NEW_TASK|MULTIPLE_TASK）不可省：2026-09-18 E2E 实测不带它时 `am start --display`
     * 会把物理屏后台已有的设置任务**搬到**虚拟屏（首帧停在用户上次看的 WLAN 页），销屏时连同用户的
     * 任务一起销毁。与 open_app（ActionCommand.openAppArgv）同一条规则。
     */
    override fun launchSettings(displayId: Int): Boolean =
        RootShell.execArgv(listOf("am", "start", "--display", displayId.toString(), "-a", "android.settings.SETTINGS", "-f", "0x18000000")).ok

    private fun tryConnect(): LocalSocket? =
        try {
            LocalSocket().apply {
                connect(LocalSocketAddress(Daemon.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            }
        } catch (_: Exception) {
            null
        }

    private fun ensureStarted() {
        // env CLASSPATH=<apk> app_process /system/bin com.androiduse.daemon.Daemon
        // apkPath 是 App 自控路径（非不可信输入），但仍走 execArgv 的 argv 形式，杜绝任何注入面。
        RootShell.execArgvAsync(
            listOf(
                "env", "CLASSPATH=$apkPath",
                "app_process", "/system/bin",
                "com.androiduse.daemon.Daemon",
            )
        )
    }

    /** 拉起后守护进程要点时间 bind socket，退避重试最多约 3 秒。 */
    private fun connectWithBackoff(): LocalSocket? {
        val delaysMs = longArrayOf(100, 200, 300, 500, 700, 1000)
        for (d in delaysMs) {
            try { Thread.sleep(d) } catch (_: InterruptedException) {}
            tryConnect()?.let { return it }
        }
        return null
    }
}
