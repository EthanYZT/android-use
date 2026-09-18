package com.androiduse.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fixture 是 2026-09-18 在 OnePlus Ace 5（PKG110）真机上抓的 `dumpsys activity activities`：
 * 守护进程建了虚拟屏 #48、在其上起了设置页，同时物理屏 display 0 有本 App 自己的任务（#210）。
 * 见 task-9-brief.md Step 2 的抓取命令。
 */
class DisplayTasksTest {
    private val dump = File("src/test/resources/dumpsys-activity-two-displays.txt").readText()

    @Test fun parsesEachDisplaysTaskIdsTopToBottomWithoutDuplicates() {
        val parsed = DisplayTasks.parse(dump)

        // 精确断言 display 0 的完整列表（而不仅是"首个 id"+"无重复"）：把 `ActivityTaskSupervisor state:`
        // 截断规则去掉的话，214（只存在于 display 48）会串进 display 0 的列表，但首个 id 仍是 210、
        // 仍然无重复——所以必须锁住完整顺序 + 214 不在其中，才能让截断规则被删时测试真的失败。
        val display0 = parsed.getValue(0)
        assertEquals(listOf(210, 1, 2, 195, 94, 160, 152, 137, 3, 5, 4, 167), display0)
        assertFalse(display0.contains(214)) // 214 只在虚拟屏 48，若截断规则失效会串进来
        assertEquals(display0.size, display0.toSet().size) // 无重复：ActivityTaskSupervisor state 里重列的那份不能算

        val display48 = parsed.getValue(48)
        assertEquals(214, display48.first())
        assertTrue(display48.contains(214)) // 214 就是本次测试起的设置页任务
    }

    @Test fun leakedToPhysicalReturnsNewIdsOnDisplay0() {
        val before = mapOf(0 to listOf(210, 1))
        val after = mapOf(0 to listOf(999, 210, 1))
        assertEquals(listOf(999), DisplayTasks.leakedToPhysical(before, after))
    }

    @Test fun leakedToPhysicalEmptyWhenUnchanged() {
        val before = mapOf(0 to listOf(210, 1))
        val after = mapOf(0 to listOf(210, 1))
        assertTrue(DisplayTasks.leakedToPhysical(before, after).isEmpty())
    }

    @Test fun leakedToPhysicalEmptyWhenNewIdOnlyOnVirtualDisplay() {
        val before = mapOf(0 to listOf(210), 48 to listOf(214))
        val after = mapOf(0 to listOf(210), 48 to listOf(214, 300))
        assertTrue(DisplayTasks.leakedToPhysical(before, after).isEmpty())
    }
}
