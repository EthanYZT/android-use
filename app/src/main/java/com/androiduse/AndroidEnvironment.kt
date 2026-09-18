package com.androiduse

import com.androiduse.actuation.Action
import com.androiduse.actuation.Injector
import android.content.Context
import android.util.Log
import com.androiduse.agent.AppEntry
import com.androiduse.agent.Environment
import com.androiduse.agent.NodeGrounding
import com.androiduse.agent.Observation
import com.androiduse.agent.SystemResult
import com.androiduse.capability.SystemCall
import com.androiduse.capability.SystemInterfaces
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
    /** 查 open_app 白名单、系统接口工具用；null 表示没有可用的 Context（列表为空，open_app/系统接口不可用）。 */
    private val context: Context?,
    /** 1d：端侧 OCR 补洞；null 表示不做 OCR（AgentCli），列表为纯节点树。 */
    private val textReader: TextReader? = null,
    /** false：Provider 类系统工具（日历/联系人）不可用，透传给 [SystemInterfaces]（AgentCli 裸 systemMain Context 用）。 */
    private val providersAvailable: Boolean = true,
) : Environment {

    private companion object { const val TAG = "AndroidEnvironment" }

    private val system: SystemInterfaces? = context?.let { SystemInterfaces(it, screen, providersAvailable) }

    override fun performSystem(call: SystemCall): SystemResult =
        system?.perform(call) ?: SystemResult(false, "没有 Context，系统接口工具不可用")

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
        var ocrLines: List<com.androiduse.agent.OcrLine>? = null
        val bmp = frame?.fullBitmap
        if (textReader != null && bmp != null) {
            val t0 = System.currentTimeMillis()
            val lines = textReader.read(bmp)
            if (lines == null) ocrError = "识别失败或超时"
            else { ocrLines = lines; merged = OcrMerge.merge(nodes, lines, screenW, screenH) }
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
            ocrLines = ocrLines,
        )
    }

    override fun perform(action: Action): Boolean = Injector.perform(action, screen)

    override fun lastError(): String? = Injector.lastTypeError

    override fun installedApps(): List<AppEntry> = context?.let { LauncherApps.query(it.packageManager) } ?: emptyList()

    override fun refreshNodes(): List<DumpCodec.NodeRecord>? =
        (DaemonClient.dump(screen.logicalDisplayId) as? DumpCodec.DumpResult.Ok)?.nodes
}
