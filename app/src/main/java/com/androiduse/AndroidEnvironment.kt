package com.androiduse

import com.androiduse.actuation.Action
import com.androiduse.actuation.Injector
import android.content.pm.PackageManager
import com.androiduse.agent.AppEntry
import com.androiduse.agent.Environment
import com.androiduse.agent.NodeGrounding
import com.androiduse.agent.Observation
import com.androiduse.daemon.DumpCodec
import com.androiduse.display.VirtualScreen
import com.androiduse.perception.ScreenCapture
import com.androiduse.root.DaemonClient

/**
 * [Environment] 的真机实现：截图走 [ScreenCapture]，节点树走 root 守护进程 [DaemonClient]，
 * 注入走 [Injector]。全部阻塞调用，AgentLoop 已在 IO 线程。
 */
class AndroidEnvironment(
    private val screen: VirtualScreen,
    /** 查 open_app 白名单用；null 表示没有可用的 PackageManager（列表为空，open_app 不可用）。 */
    private val pm: PackageManager?,
) : Environment {

    override val screenW: Int get() = screen.widthPx
    override val screenH: Int get() = screen.heightPx

    override fun observe(stepIndex: Int): Observation {
        val frame = ScreenCapture.captureAsJpegBase64(screen)
        // 节点读不到不阻断——退化为「仅截图」，模型凭视觉给坐标（DESIGN §5.1 ③级）。
        val (nodes, dumpError) = when (val d = DaemonClient.dump(screen.logicalDisplayId)) {
            is DumpCodec.DumpResult.Ok -> d.nodes to null
            is DumpCodec.DumpResult.Err -> emptyList<DumpCodec.NodeRecord>() to d.message
        }
        return Observation(
            screenshotBase64 = frame,
            screenshotPath = null,
            nodes = nodes,
            nodesBlock = NodeGrounding.promptBlock(nodes, screenW, screenH),
            dumpError = dumpError,
        )
    }

    override fun perform(action: Action): Boolean = Injector.perform(action, screen)

    override fun installedApps(): List<AppEntry> = pm?.let { LauncherApps.query(it) } ?: emptyList()

    override fun refreshNodes(): List<DumpCodec.NodeRecord>? =
        (DaemonClient.dump(screen.logicalDisplayId) as? DumpCodec.DumpResult.Ok)?.nodes
}
