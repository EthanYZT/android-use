package com.androiduse.display

import com.androiduse.root.RootShell

/** 一块可供 Agent 使用的屏幕。两个 id 的用途见 DisplayParser 注释。 */
data class VirtualScreen(
    val logicalDisplayId: Int,
    val surfaceFlingerId: Long,
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * 用 overlay_display_devices 建一块额外的 display，供 Agent 在上面跑任务，
 * 与用户正在用的 display 0 隔离（2026-09-17 实测：向新屏注入不影响 display 0 顶层 Activity）。
 *
 * 局限：叠加显示是可见窗口，不是 headless。真 headless 需要 ADD_TRUSTED_DISPLAY，
 * 留到阶段 1。阶段 0 只要求「前台可正常用机」，本方案满足。
 */
object VirtualDisplayManager {

    private const val SETTING_KEY = "overlay_display_devices"

    fun create(widthPx: Int = 1080, heightPx: Int = 2376, densityDpi: Int = 480): VirtualScreen? {
        val before = currentLogicalIds()

        val spec = "${widthPx}x${heightPx}/${densityDpi}"
        val put = RootShell.exec("settings put global $SETTING_KEY \"$spec\"")
        if (!put.ok) return null

        // 显示子系统建屏是异步的，轮询等它出现，最多 5 秒
        repeat(10) {
            Thread.sleep(500)
            val after = currentLogicalIds()
            val newId = (after - before.toSet()).minOrNull()
            if (newId != null) {
                val sfId = currentVirtualSfId() ?: return@repeat
                return VirtualScreen(newId, sfId, widthPx, heightPx)
            }
        }
        // 没等到就还原，避免留下半个状态
        destroy()
        return null
    }

    fun destroy() {
        RootShell.exec("settings put global $SETTING_KEY null")
    }

    /** 用 Intent action 在指定屏启动页面，例如 android.settings.SETTINGS。 */
    fun launchIntentAction(action: String, screen: VirtualScreen): Boolean =
        RootShell.exec("am start --display ${screen.logicalDisplayId} -a $action").ok

    private fun currentLogicalIds(): List<Int> =
        DisplayParser.parseLogicalDisplayIds(RootShell.exec("dumpsys display").stdout)

    private fun currentVirtualSfId(): Long? =
        DisplayParser.parseVirtualSurfaceFlingerId(
            RootShell.exec("dumpsys SurfaceFlinger --display-id").stdout
        )
}
