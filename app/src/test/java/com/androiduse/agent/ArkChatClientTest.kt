package com.androiduse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯聊天客户端：请求体进，Completion 出。决策逻辑不在这里。
 * 保留 2026-09-17 加的截断重试：推理模型偶发把整个 max_tokens 烧在 reasoning_content 上。
 */
class ArkChatClientTest {

    private fun lengthResponse() = """
        {"choices":[{"finish_reason":"length","message":{"reasoning_content":"...","content":"","role":"assistant"}}],
        "usage":{"completion_tokens":8192,"completion_tokens_details":{"reasoning_tokens":8190}}}
    """.trimIndent()

    private fun toolResponse() = """
        {"choices":[{"finish_reason":"tool_calls","message":{"content":"看到入口","role":"assistant",
        "tool_calls":[{"id":"c1","type":"function","function":{"name":"back","arguments":"{}"}}]}}],
        "usage":{"completion_tokens":200,"completion_tokens_details":{"reasoning_tokens":180}}}
    """.trimIndent()

    private fun client(vararg responses: String): Pair<ArkChatClient, MutableList<String>> {
        val sent = mutableListOf<String>()
        val queue = responses.toMutableList()
        val c = ArkChatClient("key", "http://x", transport = { body ->
            sent.add(body)
            ArkChatClient.HttpResult(200, queue.removeAt(0))
        })
        return c to sent
    }

    private fun ok(r: ArkChatClient.Result) = (r as ArkChatClient.Result.Ok).completion
    private fun err(r: ArkChatClient.Result) = (r as ArkChatClient.Result.Err).message

    @Test
    fun returnsCompletionWithToolCallsAndNote() {
        val (c, sent) = client(toolResponse())
        val comp = ok(c.chat("{\"body\":1}"))
        assertEquals("看到入口", comp.content)
        assertEquals(listOf(ToolCall("c1", "back", "{}")), comp.toolCalls)
        assertEquals(180, comp.reasoningTokens)
        assertEquals(listOf("{\"body\":1}"), sent)
    }

    @Test
    fun retriesOnceWhenReplyIsTruncatedByLength() {
        val (c, sent) = client(lengthResponse(), toolResponse())
        assertEquals("back", ok(c.chat("{}")).toolCalls[0].name)
        assertEquals(2, sent.size)
    }

    @Test
    fun reportsTruncationDistinctlyWhenRetryIsAlsoTruncated() {
        val (c, sent) = client(lengthResponse(), lengthResponse())
        val m = err(c.chat("{}"))
        assertEquals(2, sent.size)
        assertTrue(m, m.contains("截断") && m.contains("length") && m.contains("8190"))
    }

    @Test
    fun httpErrorAndTransportExceptionBecomeErr() {
        val http = ArkChatClient("k", "u", transport = { ArkChatClient.HttpResult(401, "{\"error\":\"bad key\"}") })
        assertTrue(err(http.chat("{}")).contains("401"))
        val boom = ArkChatClient("k", "u", transport = { throw java.io.IOException("timeout") })
        assertTrue(err(boom.chat("{}")).contains("timeout"))
    }

    @Test
    fun blankApiKeyIsErrWithoutSendingAnything() {
        val blank = ArkChatClient("", "http://x", transport = { throw AssertionError("不应发请求") })
        assertTrue(err(blank.chat("{}")).contains("apiKey"))
    }

    @Test
    fun readTimeoutCoversAFullLengthReplyAtObservedSpeed() {
        // 实测生成速度约 48 token/s；读超时若等不到 MAX_TOKENS 的回复，长回复会变成"请求异常: timeout"。
        val neededSeconds = PromptBuilder.MAX_TOKENS / 48
        assertTrue(ArkChatClient.READ_TIMEOUT_SECONDS >= neededSeconds)
    }
}
