package com.androiduse.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayParserTest {

    // Decoy values (5, 7) are intentionally distinct from the expected result (0, 3)
    // so the test fails if the regex over-matches beyond mDisplayId= (e.g., matching
    // any "displayId" pattern). An over-broad regex would extract 0, 3, 5, 7
    // and the assertion would fail.
    private val dumpsysDisplayWithVirtual = """
          mViewports=[DisplayViewport{type=INTERNAL, valid=true, displayId=5, uniqueId='local:4630946652107814787'}]
          Display 0:
            mDisplayId=0
            mBaseDisplayInfo=DisplayInfo{"内置屏幕", displayId 5}
          Display 3:
            mDisplayId=3
            mBaseDisplayInfo=DisplayInfo{"叠加视图 #1", displayId 7}
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

    // F-5: 两块虚拟屏同时存在的场景（例如残留了一块孤儿虚拟屏时），用来测试
    // parseVirtualSurfaceFlingerIds 能把两个都列出来，而不是像 parseVirtualSurfaceFlingerId
    // 那样只给第一个。
    private val sfWithTwoVirtualDisplays = """
        Display 4630946652107814787 (HWC display 0): port=131 pnpId=QCM displayName=""
        Display 11529215046336967767 (Virtual display): displayName="叠加视图 #1"
        Display 11529215048992808472 (Virtual display): displayName="叠加视图 #2"
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

    // --- F-5: parseVirtualSurfaceFlingerIds，支持建屏前后做对称的集合差 ---

    @Test
    fun parsesAllVirtualSurfaceFlingerIdsWhenMultiplePresent() {
        assertEquals(
            listOf(11529215046336967767uL.toLong(), 11529215048992808472uL.toLong()),
            DisplayParser.parseVirtualSurfaceFlingerIds(sfWithTwoVirtualDisplays),
        )
    }

    @Test
    fun parseVirtualSurfaceFlingerIdsReturnsEmptyListWhenNoneExist() {
        assertEquals(emptyList<Long>(), DisplayParser.parseVirtualSurfaceFlingerIds(sfOnlyPhysical))
    }

    @Test
    fun parseVirtualSurfaceFlingerIdSingularStaysConsistentWithFirstOfTheListVariant() {
        // F-5 把单数版本改成了基于列表版本的 firstOrNull()，这里锁住"仍是同一个第一个"的
        // 行为不变——parseVirtualSurfaceFlingerId 的既有调用方（ensureNoStaleVirtualDisplay
        // 的"是否存在虚拟屏"判断）不需要跟着改。
        assertEquals(
            DisplayParser.parseVirtualSurfaceFlingerIds(sfWithTwoVirtualDisplays).first(),
            DisplayParser.parseVirtualSurfaceFlingerId(sfWithTwoVirtualDisplays),
        )
    }
}
