package com.androiduse

import com.androiduse.display.ScreenSessionCore
import com.androiduse.display.VirtualScreen
import com.androiduse.root.DaemonClient

/**
 * 进程内唯一的虚拟屏会话（真机接线）。状态机见 [ScreenSessionCore]。
 *
 * 生命周期：主屏执行任务时若无屏则自动建屏；任务结束保留屏以便连续任务；
 * MainActivity 真正结束（isFinishing）时销毁。即便没调到 destroy，App 进程死 → 租约 EOF →
 * 守护进程自己销屏（spec 1e §4.1）。
 *
 * 全部是阻塞调用（LocalSocket / root shell），必须在 IO 线程调用。
 */
object ScreenSession {
    private val core = ScreenSessionCore(DaemonClient)

    val screen: VirtualScreen? get() = core.screen

    fun ensure(): VirtualScreen? = core.ensure()

    fun destroy(): Boolean = core.destroy()
}
