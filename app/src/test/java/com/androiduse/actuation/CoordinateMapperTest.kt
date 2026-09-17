package com.androiduse.actuation

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {

    @Test
    fun mapsMidpointToHalfOfSize() {
        assertEquals(540, CoordinateMapper.toPixels(500, 1080))
    }

    @Test
    fun mapsZeroToZero() {
        assertEquals(0, CoordinateMapper.toPixels(0, 1080))
    }

    @Test
    fun mapsMaxToLastPixelNotOutOfBounds() {
        // 1000 应落在最后一个有效像素上，而不是 1080（越界）
        assertEquals(1079, CoordinateMapper.toPixels(1000, 1080))
    }

    @Test
    fun clampsValuesAboveRange() {
        assertEquals(1079, CoordinateMapper.toPixels(1500, 1080))
    }

    @Test
    fun clampsNegativeValues() {
        assertEquals(0, CoordinateMapper.toPixels(-20, 1080))
    }

    @Test
    fun mapsVerticalAxisWithItsOwnSize() {
        assertEquals(1188, CoordinateMapper.toPixels(500, 2376))
    }
}
