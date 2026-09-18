package com.androiduse.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoverPlanTest {
    private fun t(id: Int, pkg: String?) = DisplayTasks.Task(id, pkg)

    @Test fun ordersBottomFirstSoTheTopTaskIsMovedLastAndEndsUpInFront() {
        val topToBottom = listOf(t(30, "com.sankuai.meituan.takeoutnew"), t(20, "com.android.contacts"), t(10, "com.android.settings"))
        assertEquals(listOf(10, 20, 30), HandoverPlan.order(topToBottom, "com.androiduse"))
    }

    @Test fun neverMovesOwnPackage() {
        val topToBottom = listOf(t(30, "com.autonavi.minimap"), t(21, "com.androiduse"), t(10, null))
        assertEquals(listOf(10, 30), HandoverPlan.order(topToBottom, "com.androiduse"))
    }

    @Test fun targetIsTheTopmostForeignTask() {
        assertEquals(30, HandoverPlan.targetId(listOf(t(30, "com.autonavi.minimap"), t(10, "com.android.settings")), "com.androiduse"))
        assertEquals(10, HandoverPlan.targetId(listOf(t(31, "com.androiduse"), t(10, "com.android.settings")), "com.androiduse"))
        assertNull(HandoverPlan.targetId(listOf(t(31, "com.androiduse")), "com.androiduse"))
        assertNull(HandoverPlan.targetId(emptyList(), "com.androiduse"))
    }

    @Test fun moveArgvTargetsPhysicalDisplayZero() {
        assertEquals(listOf("am", "display", "move-stack", "278", "0"), HandoverPlan.moveArgv(278))
    }
}
