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

    private val apps = listOf(
        AppEntry("时钟", "com.oplus.alarmclock/.AlarmClock"),
        AppEntry("日历", "com.coloros.calendar/.Main"),
    )

    @Test
    fun openAppResolvesLabelToComponent() {
        val r = ToolCallResolver.resolve(ToolCall("c", "open_app", """{"name":"时钟"}"""), nodes, w, h, apps)
        assertEquals(Action.OpenApp("时钟", "com.oplus.alarmclock/.AlarmClock"), ok(r))
    }

    @Test
    fun openAppUnknownNameIsErrorListingAvailableApps() {
        // 错误要回给模型，并附上可选列表，让它下一轮改名字而不是继续猜。
        val r = ToolCallResolver.resolve(ToolCall("c", "open_app", """{"name":"微信"}"""), nodes, w, h, apps)
        val m = err(r)
        assertTrue(m, m.contains("微信") && m.contains("时钟") && m.contains("日历"))
    }

    @Test
    fun openAppWithoutNameIsError() {
        assertTrue(ToolCallResolver.resolve(ToolCall("c", "open_app", "{}"), nodes, w, h, apps) is ToolCallResolver.Resolution.Err)
    }

    @Test
    fun openAppWithEmptyCatalogIsError() {
        assertTrue(ToolCallResolver.resolve(ToolCall("c", "open_app", """{"name":"时钟"}"""), nodes, w, h, emptyList()) is ToolCallResolver.Resolution.Err)
    }

    @Test
    fun typeResolvesTextWithOptionalIdAndSubmit() {
        assertEquals(Action.Type("豆包手机", 7, true),
            ok(ToolCallResolver.resolve(ToolCall("c", "type", """{"text":"豆包手机","id":7,"submit":true}"""), nodes, w, h)))
        assertEquals(Action.Type("hi", null, false),
            ok(ToolCallResolver.resolve(ToolCall("c", "type", """{"text":"hi"}"""), nodes, w, h)))
    }

    @Test
    fun typeWithoutTextOrUnknownIdIsError() {
        assertTrue(ToolCallResolver.resolve(ToolCall("c", "type", "{}"), nodes, w, h) is ToolCallResolver.Resolution.Err)
        val r = ToolCallResolver.resolve(ToolCall("c", "type", """{"text":"x","id":99}"""), nodes, w, h)
        assertTrue(err(r), err(r).contains("99"))
    }

    @Test
    fun systemToolResolvesToActionSystem() {
        val r = ToolCallResolver.resolve(ToolCall("c", "dial", """{"number":"10086"}"""), nodes, w, h)
        assertEquals(Action.System(com.androiduse.capability.SystemCall.Dial("10086")), ok(r))
    }

    @Test
    fun systemToolValidationErrorIsFedBack() {
        val r = ToolCallResolver.resolve(ToolCall("c", "calendar_create", """{"title":"x","start":"明天"}"""), nodes, w, h)
        assertTrue(err(r), err(r).contains("YYYY-MM-DD HH:mm"))
    }

    @Test
    fun calendarQueryDefaultsUseInjectedClock() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = com.androiduse.capability.TimeText.parseDateTime("2026-09-18 16:52", zone)!!
        val r = ToolCallResolver.resolve(ToolCall("c", "calendar_query", "{}"), nodes, w, h, nowMs = now, zone = zone)
        val call = (ok(r) as Action.System).call as com.androiduse.capability.SystemCall.CalendarQuery
        assertEquals(com.androiduse.capability.TimeText.parseDateTime("2026-09-18 00:00", zone)!!, call.fromMs)
    }
}
