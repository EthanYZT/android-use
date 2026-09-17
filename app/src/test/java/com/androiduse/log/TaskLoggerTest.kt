package com.androiduse.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLoggerTest {

    @Test
    fun formatsStepWithAllFields() {
        val logger = TaskLogger()
        val line = logger.step(1, "Tap(500,620)", "gui", 1234, "点显示与亮度")
        assertTrue(line.contains("#1"))
        assertTrue(line.contains("Tap(500,620)"))
        assertTrue(line.contains("gui"))
        assertTrue(line.contains("1234ms"))
        assertTrue(line.contains("点显示与亮度"))
    }

    @Test
    fun accumulatesLinesInOrder() {
        val logger = TaskLogger()
        logger.step(1, "Tap(1,1)", "gui", 10, "a")
        logger.step(2, "Back", "gui", 20, "b")
        assertEquals(2, logger.lines.size)
        assertTrue(logger.lines[0].contains("#1"))
        assertTrue(logger.lines[1].contains("#2"))
    }
}
