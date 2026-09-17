package com.androiduse.root

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.androiduse.daemon.Daemon
import com.androiduse.daemon.DumpCodec
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
object DaemonClient {

    private const val TAG = "DaemonClient"

    /** App 的 APK 路径（sourceDir）。由初始化时注入，与 ScreenCapture.cacheDir 一致的做法。 */
    lateinit var apkPath: String

    /** dump 指定逻辑屏的节点树。失败返回 [DumpCodec.DumpResult.Err]。 */
    fun dump(displayId: Int): DumpCodec.DumpResult {
        // 先直接试连（守护进程可能已在跑）；连不上再拉起、退避重试。
        var socket = tryConnect()
        if (socket == null) {
            Log.i(TAG, "daemon not up, launching")
            ensureStarted()
            socket = connectWithBackoff()
        }
        if (socket == null) return DumpCodec.DumpResult.Err("daemon unreachable")

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
