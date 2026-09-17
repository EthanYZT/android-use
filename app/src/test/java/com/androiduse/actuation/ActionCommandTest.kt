package com.androiduse.actuation

import com.androiduse.display.VirtualScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionCommandTest {

    private val screen = VirtualScreen(
        logicalDisplayId = 3,
        surfaceFlingerId = 11529215046336967767uL.toLong(),
        widthPx = 1080,
        heightPx = 2376,
    )

    @Test
    fun tapUsesLogicalDisplayIdAndPixelCoords() {
        assertEquals(
            "input -d 3 tap 540 1188",
            ActionCommand.toShell(Action.Tap(500, 500), screen),
        )
    }

    @Test
    fun swipeIncludesDuration() {
        assertEquals(
            "input -d 3 swipe 540 1782 540 594 300",
            ActionCommand.toShell(Action.Swipe(500, 750, 500, 250, 300), screen),
        )
    }

    @Test
    fun backMapsToKeyevent4() {
        assertEquals("input -d 3 keyevent 4", ActionCommand.toShell(Action.Back, screen))
    }

    @Test
    fun homeIsRefusedByConstructionNotJustByCaller() {
        // F-3: 曾经这条测试断言 Home 产出 "input -d 3 keyevent 3"，把危险的映射锁进了测试里。
        // 2026-09-17 真机实测证明这条命令不受 -d 隔离，会把物理屏(display 0)从本 App 切到
        // 桌面 Launcher。拒绝点现在落在 toShell 本身(见其对 Action.Home 分支的注释)，
        // 不是只在上层 AgentLoop 里挡一下——任何调用 Injector.perform 的路径都无法绕过。
        assertNull(ActionCommand.toShell(Action.Home, screen))
    }

    @Test
    fun waitProducesNoShellCommand() {
        assertNull(ActionCommand.toShell(Action.Wait(500), screen))
    }

    @Test
    fun finishProducesNoShellCommand() {
        assertNull(ActionCommand.toShell(Action.Finish("已打开显示与亮度"), screen))
    }
}
