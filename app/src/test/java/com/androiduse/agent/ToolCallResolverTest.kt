package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tool call → Action。解析失败不再是"整个任务中止"的理由：返回 Err 文本，由 AgentLoop
 * 写进 tool 结果反馈给模型，让它下一轮自己纠正。
 */
class ToolCallResolverTest {

    private val w = 1000; private val h = 2000
    private val nodes = listOf(
        NodeRecord(0, 0, 0, 100, 100, "返回", "", "", "", true, false),
        NodeRecord(7, 0, 900, 1000, 1100, "关于本机", "", "", "", true, false),
        NodeRecord(9, 0, 500, 0, 500, "退化", "", "", "", true, false), // bounds 退化
    )

    private fun ok(r: ToolCallResolver.Resolution): Action = (r as ToolCallResolver.Resolution.Ok).action
    private fun err(r: ToolCallResolver.Resolution): String = (r as ToolCallResolver.Resolution.Err).message

    @Test
    fun tapByIdResolvesToNodeCenter() {
        val r = ToolCallResolver.resolve(ToolCall("c", "tap", """{"id":7}"""), nodes, w, h)
        assertEquals(Action.Tap(500, 500), ok(r))
    }

    @Test
    fun tapByUnknownIdIsErrorMentioningTheId() {
        val r = ToolCallResolver.resolve(ToolCall("c", "tap", """{"id":99}"""), nodes, w, h)
        assertTrue(err(r), err(r).contains("99"))
    }

    @Test
    fun tapByDegenerateNodeIsError() {
        val r = ToolCallResolver.resolve(ToolCall("c", "tap", """{"id":9}"""), nodes, w, h)
        assertTrue(r is ToolCallResolver.Resolution.Err)
    }

    @Test
    fun tapByCoordinatesResolvesDirectly() {
        val r = ToolCallResolver.resolve(ToolCall("c", "tap", """{"x":120,"y":880}"""), nodes, w, h)
        assertEquals(Action.Tap(120, 880), ok(r))
    }

    @Test
    fun tapWithNeitherIdNorCoordinatesIsError() {
        val r = ToolCallResolver.resolve(ToolCall("c", "tap", """{}"""), nodes, w, h)
        assertTrue(r is ToolCallResolver.Resolution.Err)
    }

    @Test
    fun swipeResolvesWithDefaultDuration() {
        val r = ToolCallResolver.resolve(ToolCall("c", "swipe", """{"x1":500,"y1":800,"x2":500,"y2":300}"""), nodes, w, h)
        assertEquals(Action.Swipe(500, 800, 500, 300, 300), ok(r))
    }

    @Test
    fun backWaitAndFinishResolve() {
        assertEquals(Action.Back, ok(ToolCallResolver.resolve(ToolCall("c", "back", "{}"), nodes, w, h)))
        assertEquals(Action.Wait(Action.Wait.clamp(1500)), ok(ToolCallResolver.resolve(ToolCall("c", "wait", """{"ms":1500}"""), nodes, w, h)))
        assertEquals(Action.Finish("型号 一加 Ace 5"), ok(ToolCallResolver.resolve(ToolCall("c", "finish", """{"summary":"型号 一加 Ace 5"}"""), nodes, w, h)))
    }

    @Test
    fun homeAndUnknownToolsAreErrors() {
        assertTrue(ToolCallResolver.resolve(ToolCall("c", "home", "{}"), nodes, w, h) is ToolCallResolver.Resolution.Err)
        assertTrue(ToolCallResolver.resolve(ToolCall("c", "teleport", "{}"), nodes, w, h) is ToolCallResolver.Resolution.Err)
    }
}
