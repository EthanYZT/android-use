package com.androiduse.actuation

/**
 * Agent 可执行的动作。坐标一律是归一化到 [0,1000] 的整数，与截图缩放解耦。
 * 阶段 0 只支持最小集合；输入文本、长按等留到阶段 2 以后。
 */
sealed class Action {
    data class Tap(val xNorm: Int, val yNorm: Int) : Action()
    data class Swipe(
        val x1Norm: Int, val y1Norm: Int,
        val x2Norm: Int, val y2Norm: Int,
        val durationMs: Int = 300,
    ) : Action()
    data object Back : Action()
    data object Home : Action()
    data class Wait(val ms: Int) : Action()
    /** 任务完成。summary 是模型对结果的自述，用于日志与验收。 */
    data class Finish(val summary: String) : Action()
}
