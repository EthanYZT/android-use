package com.androiduse.capability

/**
 * 接管（takeover）纯逻辑：用户点"在手机上继续"后，把虚拟屏上的任务搬到物理屏 display 0。
 * 顺序从底到顶——顶层任务最后搬，于是它在物理屏前台，其余落到后台/最近任务。
 * 本 App 自己的任务永远不搬。命令：`am display move-stack <taskId> 0`（本 ROM 无 `am stack move-stack`）。
 */
object HandoverPlan {
    const val PHYSICAL_DISPLAY = 0

    /** 要搬的任务 id，按执行顺序（底→顶）。输入是 DisplayTasks.parse 的自顶向下列表。 */
    fun order(tasksTopToBottom: List<DisplayTasks.Task>, ownPkg: String): List<Int> =
        tasksTopToBottom.filter { it.pkg != ownPkg }.map { it.id }.asReversed()

    /** 用户要接手的那一页：顶层非本包任务；没有则 null（无可接管）。 */
    fun targetId(tasksTopToBottom: List<DisplayTasks.Task>, ownPkg: String): Int? =
        tasksTopToBottom.firstOrNull { it.pkg != ownPkg }?.id

    fun moveArgv(taskId: Int): List<String> = listOf("am", "display", "move-stack", taskId.toString(), PHYSICAL_DISPLAY.toString())
}
