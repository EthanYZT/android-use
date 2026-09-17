package com.androiduse

import com.androiduse.display.VirtualDisplayManager
import com.androiduse.display.VirtualScreen

/**
 * 进程内唯一的虚拟屏会话：主屏"执行"和设置页的建/销按钮操作同一块屏。
 *
 * 生命周期：主屏执行任务时若无屏则自动建屏；任务结束保留屏以便连续任务；
 * MainActivity 真正结束（isFinishing）时销毁——overlay_display_devices 是跨重启持久化的
 * 全局 setting，不清理会留下一块永远存在的悬浮窗（见 VirtualDisplayManager 顶部注释）。
 *
 * 全部是阻塞调用（root shell），必须在 IO 线程调用。
 */
object ScreenSession {

    @Volatile
    var screen: VirtualScreen? = null
        private set

    /**
     * 有屏就直接返回；没有就建屏并打开系统设置——open_app 动作落地之前的过渡，
     * 让任务有一个可操作的起点，而不是对着镜像物理屏的空屏发呆。
     */
    fun ensure(): VirtualScreen? {
        screen?.let { return it }
        val s = VirtualDisplayManager.create() ?: return null
        VirtualDisplayManager.launchIntentAction("android.settings.SETTINGS", s)
        Thread.sleep(1500) // 等设置页起来，首帧截图才不是空的
        screen = s
        return s
    }

    fun destroy(): Boolean {
        val ok = VirtualDisplayManager.destroy()
        if (ok) screen = null
        return ok
    }
}
