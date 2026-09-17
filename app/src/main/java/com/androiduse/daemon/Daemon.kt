package com.androiduse.daemon

import android.app.UiAutomation
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
 * 连接策略：**首次 dump 时连一次 UiAutomation，之后复用，进程退出/空闲关闭时才 disconnect**。
 *
 * 本来 spec §3 设计点 1 想「每次 dump 连接即用用完即断」以求隐蔽（连接期 AccessibilityManager
 * .isEnabled() 对全系统为 true）。但 2026-09-17 真机 E2E 实测：**同一进程里反复
 * connect/disconnect，第二次起的 UiAutomation getWindowsOnAllDisplays 返回空**（spike 每次是
 * 全新进程，没暴露这个问题）。所以改成持连接——牺牲一点隐蔽换正确性（暴露窗口从「单次 dump」
 * 变成「守护进程存活期」，而它空闲 60s 就自杀，边界可接受）。连接若失效（dump 抛异常）会重置
 * 并在下次 dump 重连。
 */
object Daemon {

    const val SOCKET_NAME = "androiduse_daemon_v1"
    private const val IDLE_TIMEOUT_MS = 60_000L

    private val lastActivity = AtomicLong(System.currentTimeMillis())

    // 持有的 UiAutomation 连接与承载其回调的 looper。首次 dump 建立，复用。
    private var ua: UiAutomation? = null
    private var callbackLooper: Looper? = null

    @JvmStatic
    fun main(args: Array<String>) {
        // 必须在最前面：裸 app_process 没有 main looper，UiAutomation 回调线程会 NPE 崩溃。
        Looper.prepareMainLooper()
        log("starting, uid=" + android.os.Process.myUid())

        callbackLooper = HandlerThread("aud-ua").apply { start() }.looper

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
                handle(client)
            } catch (e: Throwable) {
                log("handle error: $e")
            } finally {
                try { client.close() } catch (_: Throwable) {}
            }
            lastActivity.set(System.currentTimeMillis())
        }
    }

    private fun handle(client: LocalSocket) {
        val reader = BufferedReader(InputStreamReader(client.inputStream, StandardCharsets.UTF_8))
        val line = reader.readLine() ?: return
        val req = DumpCodec.parseRequest(line)
        val response = if (req == null) {
            DumpCodec.encodeError("bad request: ${line.take(80)}")
        } else {
            runDump(req)
        }
        val out = client.outputStream
        out.write((response + "\n").toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    /** 拿到（必要时新建）复用的 UiAutomation 连接。 */
    private fun ensureConnected(): UiAutomation {
        ua?.let { return it }
        val fresh = UiAutomationFactory.connect(callbackLooper!!)
        ua = fresh
        log("UiAutomation connected (persistent)")
        return fresh
    }

    /** 连接失效时重置，下次 dump 会重连。 */
    private fun resetConnection() {
        ua?.let { UiAutomationFactory.disconnect(it) }
        ua = null
    }

    private fun runDump(req: DumpCodec.DumpRequest): String {
        val automation = try {
            ensureConnected()
        } catch (e: Throwable) {
            resetConnection()
            return DumpCodec.encodeError("connect failed: $e")
        }
        return try {
            // 窗口缓存靠事件填充，等它稳定再读（spike 结论：读前需 settle）。
            try { automation.waitForIdle(400L, 3000L) } catch (_: Throwable) {}
            val nodes = NodeExtractor.extractForDisplay(automation, req.displayId)
            DumpCodec.encodeOk(req.displayId, nodes)
        } catch (e: Throwable) {
            resetConnection() // 连接可能已死，下次重连
            DumpCodec.encodeError("dump failed: $e")
        }
    }

    private fun startIdleWatchdog() {
        Thread {
            while (true) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) {}
                if (System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                    log("idle ${IDLE_TIMEOUT_MS}ms, exiting")
                    resetConnection() // 退出前断开，撤掉「有自动化在跑」的系统状态
                    System.exit(0)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun log(msg: String) = android.util.Log.i("AudDaemon", msg)
}
