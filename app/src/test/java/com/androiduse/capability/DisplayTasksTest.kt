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

    /** 本 App 自己的包名，测 [DisplayTasks.leakedToPhysical] 的 `ownPkg` 参数用。 */
    private val own = "com.androiduse"

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
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, own), DisplayTasks.Task(1, null)))
        val after = mapOf(0 to listOf(DisplayTasks.Task(999, "com.android.mms"), DisplayTasks.Task(210, own), DisplayTasks.Task(1, null)))
        assertEquals(listOf(999), DisplayTasks.leakedToPhysical(before, after, null, own))
    }

    @Test fun leakedToPhysicalOnlyKeepsNewTasksMatchingTheLaunchedPackage() {
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, own)))
        val after = mapOf(
            0 to listOf(DisplayTasks.Task(999, "com.other.app"), DisplayTasks.Task(210, own)),
        )
        // 新任务包名和我们启动的包（com.android.mms）不同——已知包名时不算泄漏。
        assertTrue(DisplayTasks.leakedToPhysical(before, after, "com.android.mms", own).isEmpty())
    }

    @Test fun leakedToPhysicalKeepsNewTaskMatchingTheLaunchedPackage() {
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, own)))
        val after = mapOf(
            0 to listOf(DisplayTasks.Task(999, "com.android.mms"), DisplayTasks.Task(210, own)),
        )
        assertEquals(listOf(999), DisplayTasks.leakedToPhysical(before, after, "com.android.mms", own))
    }

    @Test fun leakedToPhysicalEmptyWhenUnchanged() {
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, own), DisplayTasks.Task(1, null)))
        val after = mapOf(0 to listOf(DisplayTasks.Task(210, own), DisplayTasks.Task(1, null)))
        assertTrue(DisplayTasks.leakedToPhysical(before, after, null, own).isEmpty())
    }

    @Test fun leakedToPhysicalEmptyWhenNewIdOnlyOnVirtualDisplay() {
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, own)), 48 to listOf(DisplayTasks.Task(214, "com.android.settings.root")))
        val after = mapOf(
            0 to listOf(DisplayTasks.Task(210, own)),
            48 to listOf(DisplayTasks.Task(214, "com.android.settings.root"), DisplayTasks.Task(300, "com.android.settings.root")),
        )
        assertTrue(DisplayTasks.leakedToPhysical(before, after, null, own).isEmpty())
    }

    @Test fun leakedToPhysicalDetectsExistingTaskOfLaunchedPackageBroughtToTop() {
        // 泄漏检查逻辑（I3）：置顶变了，即便这个任务 before 里就有——不是"新"任务，
        // 而是同一个已经在物理屏后台的任务被我们刚起的这次调用带到了最前。
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, own), DisplayTasks.Task(555, "com.android.mms")))
        val after = mapOf(0 to listOf(DisplayTasks.Task(555, "com.android.mms"), DisplayTasks.Task(210, own)))
        assertEquals(listOf(555), DisplayTasks.leakedToPhysical(before, after, "com.android.mms", own))
    }

    @Test fun leakedToPhysicalIgnoresTopChangeWhenNewTopPackageIsUnrelated() {
        // 置顶变了，但新置顶任务的包名和我们启动的包（已知）对不上——不算泄漏。
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, own), DisplayTasks.Task(555, "com.other.app")))
        val after = mapOf(0 to listOf(DisplayTasks.Task(555, "com.other.app"), DisplayTasks.Task(210, own)))
        assertTrue(DisplayTasks.leakedToPhysical(before, after, "com.android.mms", own).isEmpty())
    }

    // --- ownPkg 永远排除本 App 自己的任务（回归修复：pkg 未知时不能把本 App 自己当成泄漏） ---

    @Test fun leakedToPhysicalNeverFlagsOwnPackageWhenItBecomesTopAndLaunchedPkgIsUnknown() {
        // (a) pkg 未知（比如 navigate 没装地图）时，物理屏置顶变成了本 App 自己已有的任务
        // （比如某个陌生任务消失、本 App 的任务重新露出来置顶）——这是正常前台切换，不是泄漏。
        val before = mapOf(0 to listOf(DisplayTasks.Task(777, "com.other.app"), DisplayTasks.Task(210, own)))
        val after = mapOf(0 to listOf(DisplayTasks.Task(210, own), DisplayTasks.Task(777, "com.other.app")))
        assertTrue(DisplayTasks.leakedToPhysical(before, after, null, own).isEmpty())
    }

    @Test fun leakedToPhysicalNeverFlagsANewOwnPackageTaskWhenLaunchedPkgIsUnknown() {
        // (b) pkg 未知时，一个全新的本 App 自己的任务出现在 display 0（不在 before 里、也不置顶）——
        // 同样不能算泄漏：本 App 本来就跑在物理屏上，"新任务"这条规则不能拿自己的包开刀。
        val before = mapOf(0 to listOf(DisplayTasks.Task(1, "com.other.app")))
        val after = mapOf(0 to listOf(DisplayTasks.Task(1, "com.other.app"), DisplayTasks.Task(888, own)))
        assertTrue(DisplayTasks.leakedToPhysical(before, after, null, own).isEmpty())
    }

    @Test fun leakedToPhysicalStillFlagsUnrelatedUnknownPackageWhenLaunchedPkgIsUnknown() {
        // (c) 既有行为不能被 ownPkg 排除误伤：pkg 未知时，一个包名也未知（不是本 App）的新任务
        // 出现在 display 0，仍然算泄漏——ownPkg 排除只挡本 App 自己，不挡"查不到包名"的陌生任务。
        val before = mapOf(0 to listOf(DisplayTasks.Task(210, own)))
        val after = mapOf(0 to listOf(DisplayTasks.Task(210, own), DisplayTasks.Task(999, null)))
        assertEquals(listOf(999), DisplayTasks.leakedToPhysical(before, after, null, own))
    }
}
