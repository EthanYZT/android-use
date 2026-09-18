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

    /** 一次 dump，按屏拆分；tasksOn 只是取其中一屏，避免两次 dump 之间状态不一致。 */
    private fun tasksByDisplay(): Map<Int, List<DisplayTasks.Task>> =
        DisplayTasks.parse(RootShell.execArgv(listOf("dumpsys", "activity", "activities")).stdout)

    private fun tasksOn(displayId: Int): List<DisplayTasks.Task> = tasksByDisplay()[displayId] ?: emptyList()

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
        val dump1 = tasksByDisplay()
        return when (val v = HandoverPlan.verify(dump1[HandoverPlan.PHYSICAL_DISPLAY] ?: emptyList(), dump1[s.logicalDisplayId] ?: emptyList(), target, OWN_PKG)) {
            HandoverPlan.Verify.Ok -> {
                ScreenSession.destroy()
                Result.success(Unit)
            }
            is HandoverPlan.Verify.NotOnTop -> {
                // 再搬一次目标本身（比如它到了 display 0 但没置顶），再 dump 一次核对。
                val r = RootShell.execArgv(HandoverPlan.moveArgv(target))
                if (!r.ok) Log.w(TAG, "re-move-stack $target failed: ${r.stderr.ifBlank { r.stdout }}")
                val dump2 = tasksByDisplay()
                val v2 = HandoverPlan.verify(dump2[HandoverPlan.PHYSICAL_DISPLAY] ?: emptyList(), dump2[s.logicalDisplayId] ?: emptyList(), target, OWN_PKG)
                if (v2 == HandoverPlan.Verify.Ok) {
                    ScreenSession.destroy()
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException(
                        if (v.leftOnVirtual.isEmpty()) "页面已搬到手机，但没能置顶；请从最近任务打开"
                        else "页面已搬到手机但没能置顶，且虚拟屏上还有任务 #${v.leftOnVirtual}；请从最近任务打开"
                    ))
                }
            }
            is HandoverPlan.Verify.NotMoved ->
                Result.failure(IllegalStateException("没能把页面搬到手机屏幕（目标 #$target 仍在虚拟屏，虚拟屏上还有: ${v.leftOnVirtual}）"))
        }
    }
}
