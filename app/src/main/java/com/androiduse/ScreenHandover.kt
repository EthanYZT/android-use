package com.androiduse

import android.util.Log
import com.androiduse.capability.DisplayTasks
import com.androiduse.capability.HandoverPlan
import com.androiduse.root.RootShell

/**
 * 接管：把虚拟屏上的任务搬到物理屏（spec handoff §4）。只在用户点"在手机上继续"后调用；
 * 全部阻塞（root shell），必须在 IO 线程。搬完核对顶层任务已在 display 0 第一位，再销毁已空的虚拟屏。
 */
object ScreenHandover {
    private const val TAG = "ScreenHandover"
    private const val OWN_PKG = BuildConfig.APPLICATION_ID

    private fun tasksOn(displayId: Int): List<DisplayTasks.Task> =
        DisplayTasks.parse(RootShell.execArgv(listOf("dumpsys", "activity", "activities")).stdout)[displayId] ?: emptyList()

    /** 虚拟屏上有没有可接管的页面（非本 App 任务）。 */
    fun hasTakeoverTarget(): Boolean {
        val s = ScreenSession.screen ?: return false
        return HandoverPlan.targetId(tasksOn(s.logicalDisplayId), OWN_PKG) != null
    }

    fun takeover(): Result<Unit> {
        val s = ScreenSession.screen ?: return Result.failure(IllegalStateException("没有虚拟屏"))
        val tasks = tasksOn(s.logicalDisplayId)
        val target = HandoverPlan.targetId(tasks, OWN_PKG) ?: return Result.failure(IllegalStateException("虚拟屏上没有可接管的页面"))
        for (id in HandoverPlan.order(tasks, OWN_PKG)) {
            val r = RootShell.execArgv(HandoverPlan.moveArgv(id))
            if (!r.ok) Log.w(TAG, "move-stack $id failed: ${r.stderr.ifBlank { r.stdout }}")
        }
        val physical = tasksOn(HandoverPlan.PHYSICAL_DISPLAY)
        if (physical.firstOrNull()?.id != target) {
            val left = tasksOn(s.logicalDisplayId).map { it.id }
            return Result.failure(IllegalStateException("没能把页面搬到手机屏幕（目标 #$target，仍在虚拟屏: $left）"))
        }
        ScreenSession.destroy()
        return Result.success(Unit)
    }
}
