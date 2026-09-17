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
    fun homeMapsToKeyevent3() {
        assertEquals("input -d 3 keyevent 3", ActionCommand.toShell(Action.Home, screen))
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
