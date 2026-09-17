package com.androiduse.actuation

/**
 * 归一化坐标 [0,1000] → 像素坐标 [0, sizePx-1]。纯函数。
 *
 * norm/1000 表示占屏幕的比例，所以先按 sizePx 换算，再夹到最后一个有效像素：
 * norm=1000 若直接得 sizePx 会越界，input 会静默丢弃该事件。
 *
 * 注意不要写成 norm*(sizePx-1)/1000 —— 那样 500 会算成 539 而非 540，
 * 整体系统性偏小半像素到一像素。
 */
object CoordinateMapper {
    private const val NORM_MAX = 1000

    fun toPixels(norm: Int, sizePx: Int): Int {
        val clamped = norm.coerceIn(0, NORM_MAX)
        val px = (clamped.toLong() * sizePx / NORM_MAX).toInt()
        return px.coerceAtMost(sizePx - 1)
    }
}
