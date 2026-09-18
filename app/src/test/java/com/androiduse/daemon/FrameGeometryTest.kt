package com.androiduse.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ImageReader RGBA 平面常带行尾 padding（rowStride > width*4），裁剪与缩放的算术在这里单测。 */
class FrameGeometryTest {

    @Test
    fun paddedWidthIsRowStrideOverPixelStride() {
        assertEquals(1088, FrameGeometry.paddedWidth(4352, 4))
        assertEquals(1080, FrameGeometry.paddedWidth(4320, 4))
    }

    @Test
    fun needsCropOnlyWhenPadded() {
        assertTrue(FrameGeometry.needsCrop(4352, 4, 1080))
        assertFalse(FrameGeometry.needsCrop(4320, 4, 1080))
    }

    @Test
    fun scaledSizeKeepsAspectAndNeverUpscales() {
        assertEquals(720 to 1584, FrameGeometry.scaledSize(1080, 2376, 720))
        assertEquals(1080 to 2376, FrameGeometry.scaledSize(1080, 2376, 4000))
        assertEquals(1080 to 2376, FrameGeometry.scaledSize(1080, 2376, 0))
    }

    @Test
    fun scaledHeightIsAtLeastOne() {
        assertEquals(10 to 1, FrameGeometry.scaledSize(1000, 1, 10))
    }
}
