package com.androiduse.display

import com.androiduse.daemon.DaemonProtocol
import java.io.Closeable

/**
 * 虚拟屏会话状态机（spec 1e §4.2）。纯逻辑，Android 依赖都在 [DisplayService] 后面。
 *
 * ensure：租约 → 建屏 → 记屏。不再顺手起设置页：1e 之后空屏是安全黑帧（不镜像物理屏），模型开局看到
 * 空屏后直接 open_app / 专用工具打开需要的 App，省掉每个任务第一步看无关设置页、接管时多搬一个设置任务。
 * 中途失败关掉已开的租约、返回 null。已有屏时先用一次 frame 探测守护进程还活着：
 * 活着直接返回；死了（帧请求报错）说明屏已随进程消失，关旧租约后重建。
 * destroy：destroy_display + 关租约。
 *
 * 租约是保底：即便 destroy 没被调到（App 被杀），租约连接随进程断开，守护进程读到 EOF 自己销屏，
 * 不再有 overlay 方案那种跨重启残留的全局 setting。
 */
class ScreenSessionCore(private val service: DisplayService) {
    @Volatile
    var screen: VirtualScreen? = null
        private set

    private var lease: Closeable? = null

    @Synchronized
    fun ensure(w: Int = 1080, h: Int = 2376, dpi: Int = 480): VirtualScreen? {
        screen?.let { existing ->
            if (service.frame(1, 1) !is DaemonProtocol.FrameResult.Err) return existing
            closeLease()
            screen = null
        }
        val l = service.openLease() ?: return null
        val created = service.createDisplay(w, h, dpi)
        if (created !is DaemonProtocol.CreateResult.Ok) {
            l.close()
            return null
        }
        lease = l
        val s = VirtualScreen(created.displayId, created.w, created.h)
        screen = s
        return s
    }

    @Synchronized
    fun destroy(): Boolean {
        val ok = service.destroyDisplay()
        closeLease()
        screen = null
        return ok
    }

    private fun closeLease() {
        try { lease?.close() } catch (_: Exception) {}
        lease = null
    }
}
