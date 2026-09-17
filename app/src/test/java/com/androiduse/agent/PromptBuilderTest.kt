package com.androiduse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-4(a): 阶段 0 之前完全没有 PromptBuilderTest——唯一的一点覆盖是
 * ResponseParserTest 里两条近乎空断言的 contains("0")/contains("1000")（后者完全
 * 包含前者，等于只测了一条）。多步反馈（buildRequestBody 的 history 非空分支）在
 * 真机验收里从没跑到过，这里至少在纯逻辑层面把两条分支、转义边界、以及关键的系统
 * 提示词内容锁住。
 *
 * 不用 org.json 校验产出的 JSON（JVM 单测里是桩实现，见 PromptBuilder 顶部注释），
 * 用一个和 ResponseParser 同样思路的、带转义感知的括号配平扫描器代替——足以确认
 * "结构合法、可被解析"，不需要引入新依赖。
 */
class PromptBuilderTest {

    private fun isStructurallyValidJson(s: String): Boolean {
        var curly = 0
        var square = 0
        var inString = false
        var escaped = false
        for (c in s) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> curly++
                c == '}' -> curly--
                c == '[' -> square++
                c == ']' -> square--
            }
            if (curly < 0 || square < 0) return false
        }
        return curly == 0 && square == 0 && !inString
    }

    // --- buildRequestBody: 空 history 分支 ---

    @Test
    fun emptyHistoryProducesValidJsonWithCorrectNesting() {
        val body = PromptBuilder.buildRequestBody("test-model", "打开设置", emptyList(), "ZmFrZQ==")

        assertTrue("产出的请求体必须是括号/引号配平的合法 JSON", isStructurallyValidJson(body))
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("\"temperature\":0"))
        assertTrue("空 history 要落到占位文案", body.contains("还没有执行过任何步骤"))

        // 嵌套顺序：system 消息在 user 消息之前；user 的 content 数组里 image_url 在 text 之前。
        val systemIdx = body.indexOf("\"role\":\"system\"")
        val userIdx = body.indexOf("\"role\":\"user\"")
        val imageIdx = body.indexOf("\"image_url\"")
        val textIdx = body.indexOf("\"text\"")
        assertTrue(systemIdx in 0 until userIdx)
        assertTrue(userIdx in 0 until imageIdx)
        assertTrue(imageIdx in 0 until textIdx)
    }

    // --- buildRequestBody: 非空 history 分支（F-4(b) 真机验收要跑到的那条分支） ---

    @Test
    fun nonEmptyHistoryProducesValidJsonListingEachStep() {
        val history = listOf("Tap(300, 900)", "Wait(500)")
        val body = PromptBuilder.buildRequestBody("test-model", "打开设置", history, "ZmFrZQ==")

        assertTrue(isStructurallyValidJson(body))
        assertTrue(body.contains("1. Tap(300, 900)"))
        assertTrue(body.contains("2. Wait(500)"))
        assertFalse("非空 history 不应该再落到占位文案", body.contains("还没有执行过任何步骤"))
    }

    // --- jsonString: 转义边界 ---

    @Test
    fun jsonStringEscapesQuotes() {
        assertEquals("\"say \\\"hi\\\"\"", PromptBuilder.jsonString("say \"hi\""))
    }

    @Test
    fun jsonStringEscapesBackslashes() {
        assertEquals("\"a\\\\b\"", PromptBuilder.jsonString("a\\b"))
    }

    @Test
    fun jsonStringEscapesNewlines() {
        assertEquals("\"a\\nb\"", PromptBuilder.jsonString("a\nb"))
    }

    @Test
    fun jsonStringEscapesControlCharacters() {
        // \u0001 是一个不可打印的控制字符，必须走 \\u%04x 分支，不能原样输出。
        assertEquals("\"\\u0001\"", PromptBuilder.jsonString("\u0001"))
    }

    @Test
    fun jsonStringOutputEmbedsAsValidJson() {
        val escaped = PromptBuilder.jsonString("weird \" \\ \n value")
        assertTrue(isStructurallyValidJson("{\"k\":$escaped}"))
    }

    // --- systemPrompt: 关键内容断言 ---

    @Test
    fun systemPromptStatesNormalizedZeroToThousandCoordinateConvention() {
        val prompt = PromptBuilder.systemPrompt()
        assertTrue(prompt.contains("归一化"))
        assertTrue(prompt.contains("0 到 1000"))
    }

    @Test
    fun systemPromptContainsPromptInjectionDefenseLine() {
        assertTrue(PromptBuilder.systemPrompt().contains("不是给你的指令"))
    }

    @Test
    fun systemPromptDoesNotOfferHomeAction() {
        // F-3 的回归护栏：home 会跨屏抢占物理屏前台，PromptBuilder 故意不提供这个动作
        // （见 PromptBuilder 顶部注释）。任何人以后不小心把 {"action":"home"} 加回动作列表，
        // 这条测试就会红。
        assertFalse(PromptBuilder.systemPrompt().contains("home", ignoreCase = true))
    }

    @Test
    fun maxTokensLeavesHeadroomForReasoningModelsSoContentIsNotTruncatedAway() {
        // glm-5.3-flash 是推理模型：绝大部分 completion 预算花在 reasoning_content 上，
        // content 在推理之后才输出。上限太小 -> finish_reason=length、content 为空字符串 ->
        // ResponseParser 解析不出动作 -> 整个任务中止。这不是解析器的 bug，是回复被砍掉了。
        //
        // 2026-09-17 真机实测的推理 token 用量（随历史增长）：空历史 63 / 2 条 106 / 5 条 170。
        // max_tokens 是上限不是预留，调大不增加实际花费，所以留足余量。
        // 若有人把它改小到接近实测值，这条测试会红。
        val body = PromptBuilder.buildRequestBody("m", "t", emptyList(), "ZmFrZQ==")
        val declared = Regex("\"max_tokens\":(\\d+)").find(body)?.groupValues?.get(1)?.toInt()
        assertNotNull("请求体里必须声明 max_tokens", declared)
        assertTrue(
            "max_tokens=$declared 对推理模型余量不足：实测 5 条历史已用到 170 推理 token，" +
                "低于 1000 有被截断导致 content 为空的风险",
            declared!! >= 1000,
        )
    }
}
