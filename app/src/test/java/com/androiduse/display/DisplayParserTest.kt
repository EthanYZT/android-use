package com.androiduse.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayParserTest {

    private val dumpsysDisplayWithVirtual = """
          mViewports=[DisplayViewport{type=INTERNAL, valid=true, displayId=0, uniqueId='local:4630946652107814787'}]
          Display 0:
            mDisplayId=0
            mBaseDisplayInfo=DisplayInfo{"内置屏幕", displayId 0}
          Display 3:
            mDisplayId=3
            mBaseDisplayInfo=DisplayInfo{"叠加视图 #1", displayId 3}
    """.trimIndent()

    private val dumpsysDisplayOnlyPhysical = """
          Display 0:
            mDisplayId=0
    """.trimIndent()

    private val sfWithVirtual = """
        Display 4630946652107814787 (HWC display 0): port=131 pnpId=QCM displayName=""
        Display 11529215046336967767 (Virtual display): displayName="叠加视图 #1"
    """.trimIndent()

    private val sfOnlyPhysical = """
        Display 4630946652107814787 (HWC display 0): port=131 pnpId=QCM displayName=""
    """.trimIndent()

    @Test
    fun parsesAllLogicalDisplayIdsSortedAndDeduped() {
        assertEquals(listOf(0, 3), DisplayParser.parseLogicalDisplayIds(dumpsysDisplayWithVirtual))
    }

    @Test
    fun parsesSingleDisplayWhenNoVirtualExists() {
        assertEquals(listOf(0), DisplayParser.parseLogicalDisplayIds(dumpsysDisplayOnlyPhysical))
    }

    @Test
    fun parsesVirtualSurfaceFlingerId() {
        assertEquals(11529215046336967767uL.toLong(), DisplayParser.parseVirtualSurfaceFlingerId(sfWithVirtual))
    }

    @Test
    fun returnsNullWhenNoVirtualDisplayPresent() {
        assertNull(DisplayParser.parseVirtualSurfaceFlingerId(sfOnlyPhysical))
    }
}
