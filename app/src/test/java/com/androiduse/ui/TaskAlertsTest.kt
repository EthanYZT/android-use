package com.androiduse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskAlertsTest {
    @Test fun remindsEveryNStepsOnly() {
        assertFalse(TaskAlerts.shouldRemind(1))
        assertFalse(TaskAlerts.shouldRemind(19))
        assertTrue(TaskAlerts.shouldRemind(20))
        assertFalse(TaskAlerts.shouldRemind(21))
        assertTrue(TaskAlerts.shouldRemind(40))
        assertEquals(20, TaskAlerts.REMIND_EVERY_STEPS)
    }

    @Test fun stepReminderTextCarriesTheCountAndSaysItKeepsGoing() {
        val t = TaskAlerts.stepReminderText(40)
        assertTrue(t, t.contains("40 步") && t.contains("仍未完成") && t.contains("继续"))
    }

    @Test fun handoffTextCarriesTheReasonAndOutcomeTextsDistinguishFinishedFromAborted() {
        assertTrue(TaskAlerts.handoffText("停在结算页，请付款").contains("停在结算页，请付款"))
        assertEquals("任务完成", TaskAlerts.outcomeTitle(finished = true))
        assertEquals("任务中止", TaskAlerts.outcomeTitle(finished = false))
    }
}
