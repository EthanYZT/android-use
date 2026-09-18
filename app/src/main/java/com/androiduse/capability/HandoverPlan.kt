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

    sealed class Verify {
        /** 目标在 display 0 且虚拟屏已空（不再有非本包任务）。 */
        object Ok : Verify()
        /** 目标已经到了 display 0，但不在第一位（虚拟屏可能已空，也可能还有别的任务留在上面）。 */
        data class NotOnTop(val leftOnVirtual: List<Int>) : Verify()
        /** 目标仍不在 display 0（一步都没搬过去）。 */
        data class NotMoved(val leftOnVirtual: List<Int>) : Verify()
    }

    /** 搬完后的判定：physical/virtual 都是自顶向下的任务列表。只有目标在 display 0 且虚拟屏上不再有非本包任务才算 Ok。 */
    fun verify(
        physicalTopToBottom: List<DisplayTasks.Task>,
        virtualTopToBottom: List<DisplayTasks.Task>,
        target: Int,
        ownPkg: String,
    ): Verify {
        val left = virtualTopToBottom.filter { it.pkg != ownPkg }.map { it.id }
        return when {
            physicalTopToBottom.none { it.id == target } -> Verify.NotMoved(left)
            left.isNotEmpty() || physicalTopToBottom.first().id != target -> Verify.NotOnTop(left)
            else -> Verify.Ok
        }
    }
}
