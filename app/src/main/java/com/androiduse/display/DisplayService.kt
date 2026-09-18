package com.androiduse.display

import com.androiduse.daemon.DaemonProtocol
import java.io.Closeable

/**
 * ScreenSession / ScreenCapture 与守护进程之间的边界（spec 1e §4.2）。
 * 真机实现是 [com.androiduse.root.DaemonClient]，单测用假实现驱动 [ScreenSessionCore] 的状态机。
 */
interface DisplayService {
    /** 开租约连接；返回可关闭句柄（只负责 close，不能再发请求），null 表示连不上守护进程。 */
    fun openLease(): Closeable?
    fun createDisplay(w: Int, h: Int, dpi: Int): DaemonProtocol.CreateResult
    fun destroyDisplay(): Boolean
    fun frame(maxWidth: Int, quality: Int): DaemonProtocol.FrameResult
    /** 在该屏起系统设置（open_app 落地前的过渡起点，避免空黑屏）。 */
    fun launchSettings(displayId: Int): Boolean
}
