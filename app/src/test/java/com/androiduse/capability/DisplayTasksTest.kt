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

    private fun ids(tasks: List<DisplayTasks.Task>) = tasks.map { it.id }

    @Test fun parsesEachDisplaysTaskIdsTopToBottomWithoutDuplicates() {
        val parsed = DisplayTasks.parse(dump)

        // 精确断言 display 0 的完整列表（而不仅是"首个 id"+"无重复"）：把 `ActivityTaskSupervisor state:`
        // 截断规则去掉的话，214（只存在于 display 48）会串进 display 0 的列表，但首个 id 仍是 210、
        // 仍然无重复——所以必须锁住完整顺序 + 214 不在其中，才能让截断规则被删时测试真的失败。
        val display0 = parsed.getValue(0)
        assertEquals(listOf(210, 1, 2, 195, 94, 160, 152, 137, 3, 5, 4, 167), ids(display0))
        assertFalse(ids(display0).contains(214)) // 214 只在虚拟屏 48，若截断规则失效会串进来
        assertEquals(display0.size, ids(display0).toSet().size) // 无重复：ActivityTaskSupervisor state 里重列的那份不能算

        val display48 = parsed.getValue(48)
        assertEquals(214, display48.first().id)
        assertTrue(ids(display48).contains(214)) // 214 就是本次测试起的设置页任务
    }

    @Test fun parsesPackageFromAOwnerField() {
        // 根任务 #210：`A=10418:com.androiduse`。
        val display0 = DisplayTasks.parse(dump).getValue(0)
        assertEquals("com.androiduse", display0.first { it.id == 210 }.pkg)
        // display 48 的 #214：`A=1000:com.android.settings.root`。
        val display48 = DisplayTasks.parse(dump).getValue(48)
        assertEquals("com.android.settings.root", display48.first { it.id == 214 }.pkg)
    }

    @Test fun parsesPackageFromNestedIOwnerFieldWhenNoAField() {
        // 桌面 Launcher 的嵌套任务 #2：`I=com.android.launcher/.Launcher`，没有 A=。
        val display0 = DisplayTasks.parse(dump).getValue(0)
        assertEquals("com.android.launcher", display0.first { it.id == 2 }.pkg)
    }

    @Test fun packageIsNullWhenNeitherAnorIFieldPresent() {
        // 外层容器任务 #1（`type=home`）既没有 A= 也没有 I=。
        val display0 = DisplayTasks.parse(dump).getValue(0)
        assertEquals(null, display0.first { it.id == 1 }.pkg)
    }

    @Test fun leakedToPhysicalReturnsNewIdsOnDisplay0WhenPkgUnknown() {
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, "com.androiduse"), DisplayTasks.Task(1, null)))
        val after = mapOf(0 to listOf(DisplayTasks.Task(999, "com.android.mms"), DisplayTasks.Task(210, "com.androiduse"), DisplayTasks.Task(1, null)))
        assertEquals(listOf(999), DisplayTasks.leakedToPhysical(before, after, null))
    }

    @Test fun leakedToPhysicalOnlyKeepsNewTasksMatchingTheLaunchedPackage() {
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, "com.androiduse")))
        val after = mapOf(
            0 to listOf(DisplayTasks.Task(999, "com.other.app"), DisplayTasks.Task(210, "com.androiduse")),
        )
        // 新任务包名和我们启动的包（com.android.mms）不同——已知包名时不算泄漏。
        assertTrue(DisplayTasks.leakedToPhysical(before, after, "com.android.mms").isEmpty())
    }

    @Test fun leakedToPhysicalKeepsNewTaskMatchingTheLaunchedPackage() {
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, "com.androiduse")))
        val after = mapOf(
            0 to listOf(DisplayTasks.Task(999, "com.android.mms"), DisplayTasks.Task(210, "com.androiduse")),
        )
        assertEquals(listOf(999), DisplayTasks.leakedToPhysical(before, after, "com.android.mms"))
    }

    @Test fun leakedToPhysicalEmptyWhenUnchanged() {
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, "com.androiduse"), DisplayTasks.Task(1, null)))
        val after = mapOf(0 to listOf(DisplayTasks.Task(210, "com.androiduse"), DisplayTasks.Task(1, null)))
        assertTrue(DisplayTasks.leakedToPhysical(before, after, null).isEmpty())
    }

    @Test fun leakedToPhysicalEmptyWhenNewIdOnlyOnVirtualDisplay() {
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, "com.androiduse")), 48 to listOf(DisplayTasks.Task(214, "com.android.settings.root")))
        val after = mapOf(
            0 to listOf(DisplayTasks.Task(210, "com.androiduse")),
            48 to listOf(DisplayTasks.Task(214, "com.android.settings.root"), DisplayTasks.Task(300, "com.android.settings.root")),
        )
        assertTrue(DisplayTasks.leakedToPhysical(before, after, null).isEmpty())
    }

    @Test fun leakedToPhysicalDetectsExistingTaskOfLaunchedPackageBroughtToTop() {
        // 泄漏检查逻辑（I3）：置顶变了，即便这个任务 before 里就有——不是"新"任务，
        // 而是同一个已经在物理屏后台的任务被我们刚起的这次调用带到了最前。
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, "com.androiduse"), DisplayTasks.Task(555, "com.android.mms")))
        val after = mapOf(0 to listOf(DisplayTasks.Task(555, "com.android.mms"), DisplayTasks.Task(210, "com.androiduse")))
        assertEquals(listOf(555), DisplayTasks.leakedToPhysical(before, after, "com.android.mms"))
    }

    @Test fun leakedToPhysicalIgnoresTopChangeWhenNewTopPackageIsUnrelated() {
        // 置顶变了，但新置顶任务的包名和我们启动的包（已知）对不上——不算泄漏。
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, "com.androiduse"), DisplayTasks.Task(555, "com.other.app")))
        val after = mapOf(0 to listOf(DisplayTasks.Task(555, "com.other.app"), DisplayTasks.Task(210, "com.androiduse")))
        assertTrue(DisplayTasks.leakedToPhysical(before, after, "com.android.mms").isEmpty())
    }
}
