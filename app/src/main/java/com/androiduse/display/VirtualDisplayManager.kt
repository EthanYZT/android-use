package com.androiduse.display

import android.util.Log
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
 *
 * 并发：`create`/`destroy` 都对 `overlay_display_devices` 这个全局 setting 做读改写,
 * 用 `synchronized(this)` 把两者串行化, 防止两个并发调用互相踩踏(例如一个 create()
 * 的超时自愈 destroy() 冲掉另一个 create() 刚建好的屏)。
 */
object VirtualDisplayManager {

    private const val TAG = "VirtualDisplayManager"
    private const val SETTING_KEY = "overlay_display_devices"

    fun create(widthPx: Int = 1080, heightPx: Int = 2376, densityDpi: Int = 480): VirtualScreen? =
        synchronized(this) {
            // 保证进入建屏流程前不存在残留虚拟屏——否则下面用「新出现的逻辑 id」配上
            // 「(唯一的)虚拟屏 SurfaceFlinger id」时，可能把新逻辑 id 和旧虚拟屏的 sfId
            // 错误配对，产生一个两个 id 描述不同屏的 VirtualScreen（且不会报错）。
            if (!ensureNoStaleVirtualDisplay()) {
                Log.e(TAG, "建屏前清理残留虚拟屏失败, 放弃本次建屏以避免 id 被错误配对")
                return null
            }

            val before = currentLogicalIds()
            val beforeSf = currentVirtualSfIds().toSet()

            val spec = "${widthPx}x${heightPx}/${densityDpi}"
            val put = RootShell.exec("settings put global $SETTING_KEY \"$spec\"")
            if (!put.ok) return null

            // 显示子系统建屏是异步的，轮询等它出现，最多 5 秒。
            //
            // F-5：两层 id 都用"建屏前后的集合差"来找新出现的那个，保持对称——不能只对
            // 逻辑层做差集，SF 层却图省事取"第一个"（currentVirtualSfId() 是按 dump 顺序取
            // 第一条，如果设备上恰好残留一块没有逻辑屏对应的孤儿 SF 虚拟屏，会命中它而不是
            // 真正新建的那块，产出一个两个 id 描述不同屏的 VirtualScreen 且不报错）。
            // newSfIds 必须恰好是一个新增元素才配对；0 个（还没出现）或 >1 个（不止一块新增，
            // 无法确定哪个是我们刚建的）都继续重试，而不是随便挑一个。
            repeat(10) {
                Thread.sleep(500)
                val after = currentLogicalIds()
                val newId = (after - before.toSet()).minOrNull()
                if (newId != null) {
                    val newSfIds = currentVirtualSfIds().toSet() - beforeSf
                    // 本轮还没解析出恰好一个新 sfId：继续下一轮重试，不是中断整个 repeat。
                    val sfId = newSfIds.singleOrNull() ?: return@repeat
                    return VirtualScreen(newId, sfId, widthPx, heightPx)
                }
            }

            // 没等到就还原，避免留下半个状态；自愈失败时重试一次，仍失败则可见地记录
            // 下来（而不是静默吞掉），因为这意味着 overlay_display_devices 可能仍是
            // 建屏前的旧值，设备上可能残留一块没人知道的虚拟屏。
            if (!destroy() && !destroy()) {
                Log.e(TAG, "建屏超时后自愈(还原 overlay_display_devices)失败, 设备上可能残留虚拟屏, 需人工核查")
            }
            null
        }

    /** 清空 overlay_display_devices，并读回确认真的清空了。返回是否确认清空成功。 */
    fun destroy(): Boolean = synchronized(this) {
        val put = RootShell.exec("settings put global $SETTING_KEY null")
        if (!put.ok) return@synchronized false

        val readBack = RootShell.exec("settings get global $SETTING_KEY")
        readBack.ok && readBack.stdout.trim() == "null"
    }

    /** 用 Intent action 在指定屏启动页面，例如 android.settings.SETTINGS。 */
    fun launchIntentAction(action: String, screen: VirtualScreen): Boolean =
        RootShell.exec("am start --display ${screen.logicalDisplayId} -a $action").ok

    /**
     * 保证「当前没有虚拟屏，或它已被清理」，这是 [create] 能安全配对两个 id 的前提。
     * 已经没有虚拟屏时直接返回 true；否则先 destroy 再轮询等 dumpsys display 收敛到
     * 只剩一块屏，最多等 5 秒。
     */
    private fun ensureNoStaleVirtualDisplay(): Boolean {
        if (currentVirtualSfId() == null) return true

        if (!destroy() && !destroy()) return false

        repeat(10) {
            if (currentLogicalIds().size <= 1) return true
            Thread.sleep(500)
        }
        return currentLogicalIds().size <= 1
    }

    private fun currentLogicalIds(): List<Int> =
        DisplayParser.parseLogicalDisplayIds(RootShell.exec("dumpsys display").stdout)

    private fun currentVirtualSfId(): Long? =
        DisplayParser.parseVirtualSurfaceFlingerId(
            RootShell.exec("dumpsys SurfaceFlinger --display-id").stdout
        )

    private fun currentVirtualSfIds(): List<Long> =
        DisplayParser.parseVirtualSurfaceFlingerIds(
            RootShell.exec("dumpsys SurfaceFlinger --display-id").stdout
        )
}
