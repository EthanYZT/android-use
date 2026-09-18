package com.androiduse.daemon

/** ImageReader 帧 → Bitmap 的几何算术。纯函数，DisplayHost 用；Android 依赖留在 DisplayHost。 */
object FrameGeometry {
    /** RGBA 平面 rowStride/pixelStride → 承载整行的 Bitmap 宽（含行尾 padding 列）。 */
    fun paddedWidth(rowStride: Int, pixelStride: Int): Int = rowStride / pixelStride

    fun needsCrop(rowStride: Int, pixelStride: Int, imageWidth: Int): Boolean =
        paddedWidth(rowStride, pixelStride) != imageWidth

    /** 缩放到 maxWidth 后的 (w,h)；不放大；maxWidth<=0 视为不缩放。 */
    fun scaledSize(w: Int, h: Int, maxWidth: Int): Pair<Int, Int> {
        if (maxWidth <= 0 || w <= maxWidth) return w to h
        val ratio = maxWidth.toDouble() / w
        return maxWidth to (h * ratio).toInt().coerceAtLeast(1)
    }
}
