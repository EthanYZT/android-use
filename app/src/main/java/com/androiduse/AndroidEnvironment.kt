package com.androiduse

import com.androiduse.actuation.Action
import com.androiduse.actuation.Injector
import android.content.pm.PackageManager
import android.util.Log
import com.androiduse.agent.AppEntry
import com.androiduse.agent.Environment
import com.androiduse.agent.NodeGrounding
import com.androiduse.agent.Observation
import com.androiduse.daemon.DumpCodec
import com.androiduse.display.VirtualScreen
import com.androiduse.agent.OcrMerge
import com.androiduse.perception.ScreenCapture
import com.androiduse.perception.TextReader
import com.androiduse.root.DaemonClient

/**
 * [Environment] 的真机实现：截图走 [ScreenCapture]，节点树走 root 守护进程 [DaemonClient]，
 * 注入走 [Injector]。全部阻塞调用，AgentLoop 已在 IO 线程。
 */
class AndroidEnvironment(
    private val screen: VirtualScreen,
    /** 查 open_app 白名单用；null 表示没有可用的 PackageManager（列表为空，open_app 不可用）。 */
    private val pm: PackageManager?,
    /** 1d：端侧 OCR 补洞；null 表示不做 OCR（AgentCli），列表为纯节点树。 */
    private val textReader: TextReader? = null,
) : Environment {

    private companion object { const val TAG = "AndroidEnvironment" }

    override val screenW: Int get() = screen.widthPx
    override val screenH: Int get() = screen.heightPx

    override fun observe(stepIndex: Int): Observation {
        val frame = ScreenCapture.captureFrame(screen)
        // 节点读不到不阻断——退化为「仅截图」，模型凭视觉给坐标（DESIGN §5.1 ③级）。
        val (nodes, dumpError) = when (val d = DaemonClient.dump(screen.logicalDisplayId)) {
            is DumpCodec.DumpResult.Ok -> d.nodes to null
            is DumpCodec.DumpResult.Err -> emptyList<DumpCodec.NodeRecord>() to d.message
        }
        // 1d ②级：全帧 OCR 只补节点树的洞。失败/超时/空屏 → 纯节点列表，不影响本步。
        var merged = nodes
        var ocrError: String? = null
        val bmp = frame?.fullBitmap
        if (textReader != null && bmp != null) {
            val t0 = System.currentTimeMillis()
            val lines = textReader.read(bmp)
            if (lines == null) ocrError = "识别失败或超时"
            else merged = OcrMerge.merge(nodes, lines, screenW, screenH)
            Log.i(TAG, "ocr ${System.currentTimeMillis() - t0}ms lines=${lines?.size ?: -1} added=${merged.size - nodes.size}")
        }
        bmp?.recycle()
        return Observation(
            screenshotBase64 = frame?.jpegBase64,
            screenshotPath = null,
            nodes = merged,
            nodesBlock = NodeGrounding.promptBlock(merged, screenW, screenH),
            dumpError = dumpError,
            ocrCount = merged.size - nodes.size,
            ocrError = ocrError,
        )
    }

    override fun perform(action: Action): Boolean = Injector.perform(action, screen)

    override fun lastError(): String? = Injector.lastTypeError

    override fun installedApps(): List<AppEntry> = pm?.let { LauncherApps.query(it) } ?: emptyList()

    override fun refreshNodes(): List<DumpCodec.NodeRecord>? =
        (DaemonClient.dump(screen.logicalDisplayId) as? DumpCodec.DumpResult.Ok)?.nodes
}
