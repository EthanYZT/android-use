package com.androiduse.daemon

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.HandlerThread
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * root 守护进程。通过 `su root env CLASSPATH=<apk> app_process ... com.androiduse.daemon.Daemon`
 * 启动，以 App 自己的 APK 为 classpath（Daemon 类就住在 app 模块里），拿到 root + 完整
 * framework，用 [UiAutomationFactory] 读任意屏（含虚拟屏）的节点树，通过 LocalSocket 回给 App。
 *
 * v1 只有一个 RPC：dump（见 [DumpCodec]）。截图/注入/建屏仍走 shell（spec §3 ①切法）。
 *
 * 生命周期：空闲 [IDLE_TIMEOUT_MS] 无请求自杀，避免长期占用一个 root 进程；App 侧
 * [com.androiduse.root.DaemonClient] 在连不上时会重新拉起。
 *
 * 连接策略：**每次 dump 都 connect→读→disconnect**（spec §3 设计点 1，隐蔽性优先——
 * UiAutomation 连接期间 AccessibilityManager.isEnabled() 对全系统为 true，断开式把暴露压到
 * 单次 dump 的几百 ms）。若日后嫌延迟高，可改成任务期间保持连接。
 */
object Daemon {

    const val SOCKET_NAME = "androiduse_daemon_v1"
    private const val IDLE_TIMEOUT_MS = 60_000L

    private val lastActivity = AtomicLong(System.currentTimeMillis())

    @JvmStatic
    fun main(args: Array<String>) {
        // 必须在最前面：裸 app_process 没有 main looper，UiAutomation 回调线程会 NPE 崩溃。
        Looper.prepareMainLooper()
        log("starting, uid=" + android.os.Process.myUid())

        val callbackThread = HandlerThread("aud-ua").apply { start() }
        val callbackLooper = callbackThread.looper

        startIdleWatchdog()

        val server = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (e: Throwable) {
            log("bind failed (already running?): $e")
            return
        }
        log("listening on @$SOCKET_NAME")

        while (true) {
            val client = try {
                server.accept()
            } catch (e: Throwable) {
                log("accept error: $e")
                continue
            }
            lastActivity.set(System.currentTimeMillis())
            try {
                handle(client, callbackLooper)
            } catch (e: Throwable) {
                log("handle error: $e")
            } finally {
                try { client.close() } catch (_: Throwable) {}
            }
            lastActivity.set(System.currentTimeMillis())
        }
    }

    private fun handle(client: LocalSocket, callbackLooper: Looper) {
        val reader = BufferedReader(InputStreamReader(client.inputStream, StandardCharsets.UTF_8))
        val line = reader.readLine() ?: return
        val req = DumpCodec.parseRequest(line)
        val response = if (req == null) {
            DumpCodec.encodeError("bad request: ${line.take(80)}")
        } else {
            runDump(req, callbackLooper)
        }
        val out = client.outputStream
        out.write((response + "\n").toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    private fun runDump(req: DumpCodec.DumpRequest, callbackLooper: Looper): String {
        val ua = try {
            UiAutomationFactory.connect(callbackLooper)
        } catch (e: Throwable) {
            return DumpCodec.encodeError("connect failed: $e")
        }
        return try {
            // 新连接的窗口缓存靠事件填充，等它稳定再读（spike 结论：connect 后需 settle）。
            try { ua.waitForIdle(400L, 3000L) } catch (_: Throwable) {}
            val nodes = NodeExtractor.extractForDisplay(ua, req.displayId)
            DumpCodec.encodeOk(req.displayId, nodes)
        } catch (e: Throwable) {
            DumpCodec.encodeError("dump failed: $e")
        } finally {
            UiAutomationFactory.disconnect(ua)
        }
    }

    private fun startIdleWatchdog() {
        Thread {
            while (true) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) {}
                if (System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                    log("idle ${IDLE_TIMEOUT_MS}ms, exiting")
                    System.exit(0)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun log(msg: String) = android.util.Log.i("AudDaemon", msg)
}
