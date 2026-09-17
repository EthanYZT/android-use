package com.androiduse.agent

import com.androiduse.actuation.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseParserTest {

    @Test
    fun parsesTapFromCleanJson() {
        val action = ResponseParser.parseAction("""{"action":"tap","x":500,"y":620}""")
        assertEquals(Action.Tap(500, 620), action)
    }

    @Test
    fun parsesJsonWrappedInMarkdownFence() {
        // 模型经常把 JSON 包在 ```json ... ``` 里
        val raw = "好的，我先点开显示设置。\n```json\n{\"action\":\"tap\",\"x\":300,\"y\":900}\n```"
        assertEquals(Action.Tap(300, 900), ResponseParser.parseAction(raw))
    }

    @Test
    fun parsesSwipeWithDuration() {
        val action = ResponseParser.parseAction(
            """{"action":"swipe","x1":500,"y1":800,"x2":500,"y2":200,"duration":400}"""
        )
        assertEquals(Action.Swipe(500, 800, 500, 200, 400), action)
    }

    @Test
    fun parsesBackAndHome() {
        assertEquals(Action.Back, ResponseParser.parseAction("""{"action":"back"}"""))
        assertEquals(Action.Home, ResponseParser.parseAction("""{"action":"home"}"""))
    }

    @Test
    fun parsesFinishWithSummary() {
        val action = ResponseParser.parseAction("""{"action":"finish","summary":"已进入显示与亮度"}""")
        assertEquals(Action.Finish("已进入显示与亮度"), action)
    }

    @Test
    fun parsesWait() {
        assertEquals(Action.Wait(800), ResponseParser.parseAction("""{"action":"wait","ms":800}"""))
    }

    @Test
    fun returnsNullOnUnknownAction() {
        assertNull(ResponseParser.parseAction("""{"action":"teleport","x":1,"y":2}"""))
    }

    @Test
    fun returnsNullOnMalformedJson() {
        assertNull(ResponseParser.parseAction("我不知道该做什么"))
    }

    @Test
    fun extractsContentFromArkResponse() {
        val api = """
            {"choices":[{"message":{"role":"assistant","content":"{\"action\":\"home\"}"}}]}
        """.trimIndent()
        assertEquals("""{"action":"home"}""", ResponseParser.extractContent(api))
    }

    @Test
    fun extractContentReturnsNullWhenNoChoices() {
        assertNull(ResponseParser.extractContent("""{"error":{"message":"bad key"}}"""))
    }

    // 系统提示词自身的断言（坐标约定/反注入声明/不提供 home）移到了 PromptBuilderTest，
    // 那边有对应 F-4(a) 的完整覆盖，这里不再重复一条近乎空断言的测试（旧版只检查
    // contains("0") 且被 contains("1000") 完全包含，见 final-fix-report F-4）。

    // --- F-1: Wait.ms 是不可信输入，解析边界必须夹到合理范围内 ---

    @Test
    fun clampsNegativeWaitMsToZero() {
        // {"ms":-1} 若不夹紧，Injector 里的 Thread.sleep(-1) 会抛 IllegalArgumentException，
        // 没有任何调用方 catch 它，会一路崩到 MainActivity 的 lifecycleScope.launch。
        assertEquals(Action.Wait(0), ResponseParser.parseAction("""{"action":"wait","ms":-1}"""))
    }

    @Test
    fun clampsHugeWaitMsToUpperBound() {
        // {"ms":86400000}（一天）若不夹紧，会在 withContext(Dispatchers.IO) 里长时间阻塞，
        // 且 ensureActive() 只在步骤边界检查，中途取消不了——按钮会一直禁用到步数耗尽。
        assertEquals(
            Action.Wait(Action.Wait.MAX_MS),
            ResponseParser.parseAction("""{"action":"wait","ms":86400000}"""),
        )
    }

    // --- Fix round 1: robustness against malformed / adversarial model output ---

    @Test
    fun returnsNullWhenRequiredFieldIsMissing() {
        // 缺少 y，不应该抛异常，应该返回 null 让调用方重试或中止。
        assertNull(ResponseParser.parseAction("""{"action":"tap","x":500}"""))
    }

    @Test
    fun returnsNullWhenFieldHasWrongType() {
        // x 应该是数字，模型给了字符串——toIntOrNull 拿到 null，整体返回 null，不抛异常。
        assertNull(ResponseParser.parseAction("""{"action":"tap","x":"abc","y":620}"""))
    }

    @Test
    fun returnsNullOnTruncatedJsonWithoutHanging() {
        // 只有开括号没有闭括号：extractFirstJsonObject 的深度永远不会归零，
        // 循环只跑一遍 text.length 就结束，不会死循环，也不会抛异常。
        assertNull(ResponseParser.parseAction("""{"action":"tap","x":500"""))
    }

    @Test
    fun keyCollisionInStringValueDoesNotConfuseParser() {
        // summary 的值恰好是被引号包裹的 "action" 片段，正好卡在两个真实字段之间。
        // 旧的按位置查找实现会把这个值误当成字段名，顺着往后找冒号会落到 "x" 字段
        // 上，从而把整条动作解析成 null——这里断言锚定之后能正确拿到真正的
        // action（finish）和完整的 summary。
        val raw = """{"summary":"action","x":1,"action":"finish"}"""
        assertEquals(Action.Finish("action"), ResponseParser.parseAction(raw))
    }

    @Test
    fun extractContentFindsRealContentWhenReasoningContentComesFirst() {
        // 真实响应形态：这是个推理模型，message 对象里 reasoning_content /
        // encrypted_content 都在 content 前面，字段顺序不受我们控制。
        val api = """
            {"choices":[{"finish_reason":"stop","index":0,"message":{
            "reasoning_content":"\n用户现在让只回复两个字…","encrypted_content":"djHFzewr…",
            "content":"收到","role":"assistant"}}]}
        """.trimIndent()
        assertEquals("收到", ResponseParser.extractContent(api))
    }

    @Test
    fun extractCompletionReportsFinishReasonAndReasoningTokens() {
        // 2026-09-17 真机复现：推理模型把 max_tokens 全烧在 reasoning_content 上时，
        // finish_reason=length、content 为空串。只看 content 会把"被截断"误判成"解析不出"，
        // 所以要把 finish_reason 和推理 token 数一起取出来给上层判断。
        val api = """
            {"choices":[{"finish_reason":"length","index":0,"message":{
            "reasoning_content":"让我分析一下截图…","content":"","role":"assistant"}}],
            "usage":{"completion_tokens":2000,"prompt_tokens":786,
            "completion_tokens_details":{"reasoning_tokens":1998}}}
        """.trimIndent()
        val c = ResponseParser.extractCompletion(api)!!
        assertEquals("", c.content)
        assertEquals("length", c.finishReason)
        assertEquals(1998, c.reasoningTokens)
    }

    @Test
    fun extractCompletionToleratesMissingUsageAndFinishReason() {
        val api = """{"choices":[{"message":{"role":"assistant","content":"{\"action\":\"back\"}"}}]}"""
        val c = ResponseParser.extractCompletion(api)!!
        assertEquals("""{"action":"back"}""", c.content)
        assertNull(c.finishReason)
        assertNull(c.reasoningTokens)
    }

    @Test
    fun extractCompletionReturnsNullWhenNoContent() {
        assertNull(ResponseParser.extractCompletion("""{"error":{"message":"bad key"}}"""))
    }

    @Test
    fun extractCompletionReadsToolCallsWithEscapedArguments() {
        // 真实形态：arguments 是一个 JSON 字符串（内含转义引号），content 是模型的观察笔记。
        val api = """
            {"choices":[{"finish_reason":"tool_calls","index":0,"message":{"role":"assistant",
            "content":"已看到\"关于本机\"入口",
            "tool_calls":[{"id":"call_a1","type":"function","function":{"name":"tap","arguments":"{\"id\":7}"}},
                           {"id":"call_a2","type":"function","function":{"name":"finish","arguments":"{\"summary\":\"型号 \\\"一加\\\"\"}"}}]}}],
            "usage":{"completion_tokens":300,"completion_tokens_details":{"reasoning_tokens":250}}}
        """.trimIndent()
        val c = ResponseParser.extractCompletion(api)!!
        assertEquals("已看到\"关于本机\"入口", c.content)
        assertEquals("tool_calls", c.finishReason)
        assertEquals(2, c.toolCalls.size)
        assertEquals(ToolCall("call_a1", "tap", """{"id":7}"""), c.toolCalls[0])
        assertEquals("finish", c.toolCalls[1].name)
        assertEquals("""{"summary":"型号 \"一加\""}""", c.toolCalls[1].argumentsJson)
    }

    @Test
    fun extractCompletionWithNullContentAndToolCallsYieldsEmptyContent() {
        // 有些模型只发 tool_calls 时 content 为 null，不能因此判成"没有 content"。
        val api = """
            {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"c1","type":"function","function":{"name":"back","arguments":"{}"}}]}}]}
        """.trimIndent()
        val c = ResponseParser.extractCompletion(api)!!
        assertEquals("", c.content)
        assertEquals(listOf(ToolCall("c1", "back", "{}")), c.toolCalls)
    }
}
