package com.androiduse.actuation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-1: Action.Wait.clamp 是 ResponseParser（解析边界）和 Injector（注入边界，纵深防御）
 * 共用的同一个夹紧函数。这里只测函数本身的边界值，不通过 Injector.perform 间接测试
 * 巨大值——那样会让单测真的 Thread.sleep 10 秒，拖慢整个套件。
 */
class ActionTest {

    @Test
    fun clampLeavesInRangeValueUntouched() {
        assertEquals(500, Action.Wait.clamp(500))
    }

    @Test
    fun clampFloorsNegativeToZero() {
        assertEquals(0, Action.Wait.clamp(-1))
    }

    @Test
    fun clampCapsHugeValueToMax() {
        // 86400000ms = 一天，模型给出的巨大值不应该原样存进 Action.Wait。
        assertEquals(Action.Wait.MAX_MS, Action.Wait.clamp(86_400_000))
        assertEquals(10_000, Action.Wait.MAX_MS)
    }

    @Test
    fun clampAtExactBoundariesIsIdentity() {
        assertEquals(Action.Wait.MIN_MS, Action.Wait.clamp(Action.Wait.MIN_MS))
        assertEquals(Action.Wait.MAX_MS, Action.Wait.clamp(Action.Wait.MAX_MS))
    }
}
