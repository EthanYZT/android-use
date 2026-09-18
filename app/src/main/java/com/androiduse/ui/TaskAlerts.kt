package com.androiduse.ui

/** 任务提醒的纯逻辑：什么时候提醒、提醒说什么。发通知的 Android 侧在 [TaskNotifier]。 */
object TaskAlerts {
    /** 每满这么多步仍未结束就横幅提醒一次（软提示，不中止——App 内任务不限步数）。 */
    const val REMIND_EVERY_STEPS = 20

    fun shouldRemind(stepIndex: Int): Boolean = stepIndex > 0 && stepIndex % REMIND_EVERY_STEPS == 0

    fun stepReminderText(steps: Int): String = "已执行 $steps 步仍未完成，正在继续；打开查看，或点“停止任务”"

    fun handoffText(reason: String): String = "需要你接手：$reason"

    fun outcomeTitle(finished: Boolean): String = if (finished) "任务完成" else "任务中止"
}
