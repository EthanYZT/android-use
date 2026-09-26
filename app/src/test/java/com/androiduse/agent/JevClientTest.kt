package com.androiduse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Jev 传输层：请求体拼装、答案解析、重试策略。transport 注入假实现，不起网络。
 */
class JevClientTest {

    private val okBody = """
        {"model":"jev-1.13.0","answers":{
          "where":{"type":"choice","choice":"13","probabilities":{"13":0.97,"12":0.03},"confidence":0.95},
          "exists":{"type":"noul","noul":0.98}},
         "usage":{"input_tokens":788,"output_tokens":20}}
    """.trimIndent()

    private class Recorder(vararg replies: Any) {
        val queue = replies.toMutableList()
        val bodies = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        fun transport(body: String): JevClient.HttpResult {
            bodies += body
            val r = queue.removeAt(0)
            if (r is Exception) throw r
            return r as JevClient.HttpResult
        }
    }

    private fun client(rec: Recorder, key: String = "k") =
        JevClient(key, transport = { rec.transport(it) }, sleeper = { rec.sleeps += it })

    @Test
    fun bodyCarriesModelStateAndQuestionsAndAnswersAreParsed() {
        val rec = Recorder(JevClient.HttpResult(200, okBody))
        val r = client(rec).evaluate("""{"elements":["#13 x"]}""", """{"exists":{"type":"noul","instructions":"q"}}""")
        val sent = MiniJson.parse(rec.bodies.single()) as Map<*, *>
        assertEquals("jev-latest", sent["model"])
        assertEquals(listOf("#13 x"), (sent["state"] as Map<*, *>)["elements"])
        assertTrue((sent["questions"] as Map<*, *>).containsKey("exists"))

        val ok = r as JevClient.Result.Ok
        assertEquals(JevClient.Answer.Choice("13", mapOf("13" to 0.97, "12" to 0.03), 0.95), ok.answers["where"])
        assertEquals(JevClient.Answer.Noul(0.98), ok.answers["exists"])
        assertEquals("jev-1.13.0", ok.model)
        assertEquals(788, ok.inputTokens)
    }

    @Test
    fun integerProbabilitiesAreAccepted() {
        val body = """{"model":"m","answers":{"w":{"type":"choice","choice":"1","probabilities":{"1":1,"2":0},"confidence":1}},"usage":{}}"""
        val r = client(Recorder(JevClient.HttpResult(200, body))).evaluate("{}", "{}") as JevClient.Result.Ok
        assertEquals(JevClient.Answer.Choice("1", mapOf("1" to 1.0, "2" to 0.0), 1.0), r.answers["w"])
    }

    @Test
    fun blankKeyFailsWithoutCallingTransport() {
        val rec = Recorder()
        val r = client(rec, key = " ").evaluate("{}", "{}")
        assertTrue((r as JevClient.Result.Err).message.contains("typesafe.apiKey"))
        assertEquals(0, rec.bodies.size)
    }

    @Test
    fun unauthorizedIsNotRetried() {
        val rec = Recorder(JevClient.HttpResult(401, "bad key"))
        val r = client(rec).evaluate("{}", "{}")
        assertTrue((r as JevClient.Result.Err).message.contains("HTTP 401"))
        assertEquals(1, rec.bodies.size)
    }

    @Test
    fun rateLimitIsRetriedOnceHonouringRetryAfter() {
        val rec = Recorder(JevClient.HttpResult(429, "slow down", retryAfterSeconds = 1.5), JevClient.HttpResult(200, okBody))
        val r = client(rec).evaluate("{}", "{}")
        assertTrue(r is JevClient.Result.Ok)
        assertEquals(2, rec.bodies.size)
        assertEquals(listOf(1500L), rec.sleeps)
    }

    @Test
    fun retryAfterIsCappedAtTwoSeconds() {
        assertEquals(2000L, JevClient.retryWaitMs(10.0))
        assertEquals(JevClient.DEFAULT_RETRY_WAIT_MS, JevClient.retryWaitMs(null))
    }

    @Test
    fun serverErrorTwiceGivesUpAfterOneRetry() {
        val rec = Recorder(JevClient.HttpResult(503, "busy"), JevClient.HttpResult(502, "busy"))
        val r = client(rec).evaluate("{}", "{}")
        val msg = (r as JevClient.Result.Err).message
        assertTrue(msg, msg.contains("HTTP 502") && msg.contains("已重试 1 次"))
        assertEquals(2, rec.bodies.size)
    }

    @Test
    fun connectionExceptionIsRetriedOnce() {
        val rec = Recorder(java.io.IOException("reset"), JevClient.HttpResult(200, okBody))
        assertTrue(client(rec).evaluate("{}", "{}") is JevClient.Result.Ok)
        assertEquals(listOf(JevClient.DEFAULT_RETRY_WAIT_MS), rec.sleeps)
    }

    @Test
    fun malformedJsonIsUnparseableAndNotRetried() {
        val rec = Recorder(JevClient.HttpResult(200, "{not json"))
        val r = client(rec).evaluate("{}", "{}")
        assertTrue((r as JevClient.Result.Err).message.contains("无法解析"))
        assertEquals(1, rec.bodies.size)
    }

    @Test
    fun nonNumericConfidenceIsUnparseable() {
        // Review Focus 1：confidence 缺失/非数字若变成 NaN，NaN < 0.6 为 false，会被当成"确定"点下去。
        val body = """{"model":"m","answers":{"w":{"type":"choice","choice":"1","probabilities":{"1":0.9},"confidence":"high"}}}"""
        val r = client(Recorder(JevClient.HttpResult(200, body))).evaluate("{}", "{}")
        assertTrue(r is JevClient.Result.Err)
        val missing = """{"model":"m","answers":{"e":{"type":"noul"}}}"""
        assertTrue(client(Recorder(JevClient.HttpResult(200, missing))).evaluate("{}", "{}") is JevClient.Result.Err)
    }

    @Test
    fun unknownAnswerTypesAreIgnored() {
        val body = """{"model":"m","answers":{"s":{"type":"score","score":1.2},"e":{"type":"noul","noul":0.4}}}"""
        val r = client(Recorder(JevClient.HttpResult(200, body))).evaluate("{}", "{}") as JevClient.Result.Ok
        assertEquals(setOf("e"), r.answers.keys)
    }

    @Test
    fun productionRequestTargetsSystemOneWithBearerKey() {
        val req = JevClient.buildHttpRequest("secret", JevClient.DEFAULT_BASE_URL, "{}")
        assertEquals("https://api.typesafe.ai/v1/systemone", req.url.toString())
        assertEquals("Bearer secret", req.header("Authorization"))
        assertEquals("POST", req.method)
    }
}
