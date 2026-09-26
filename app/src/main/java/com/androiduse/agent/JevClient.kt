package com.androiduse.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * TypeSafe Jev（System One 结构化判断模型）客户端。**纯传输**：state + questions 进，类型化答案出；
 * 怎么问、怎么判在 [TargetLocator]。spec `docs/superpowers/specs/2026-09-26-jev-target-grounding-design.md` §3.1。
 *
 * 重试：429 / 5xx / 连接异常重试 1 次（429 按 Retry-After，最多等 2 s）；其余非 2xx 不重试。
 * 概率或置信度缺失、不是数字时整条响应判为无法解析——若放行成 NaN，`NaN < 阈值` 为 false，会被当成"确定"。
 */
class JevClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** 传输层：发请求体，回状态码+正文+Retry-After。默认 OkHttp；单测注入假实现。 */
    private val transport: (requestBody: String) -> HttpResult = OkHttpTransport(apiKey, DEFAULT_BASE_URL),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    data class HttpResult(val code: Int, val body: String, val retryAfterSeconds: Double? = null)

    sealed class Answer {
        data class Choice(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) : Answer()
        data class Noul(val value: Double) : Answer()
    }

    sealed class Result {
        data class Ok(val answers: Map<String, Answer>, val model: String, val inputTokens: Int, val latencyMs: Long) : Result()
        data class Err(val message: String) : Result()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.typesafe.ai"
        const val DEFAULT_MODEL = "jev-latest"
        const val TIMEOUT_SECONDS = 10L
        const val DEFAULT_RETRY_WAIT_MS = 300L
        const val MAX_RETRY_WAIT_MS = 2_000L

        fun retryWaitMs(retryAfterSeconds: Double?): Long =
            if (retryAfterSeconds == null || retryAfterSeconds.isNaN()) DEFAULT_RETRY_WAIT_MS
            else (retryAfterSeconds * 1000).toLong().coerceIn(0L, MAX_RETRY_WAIT_MS)

        internal fun buildHttpRequest(apiKey: String, baseUrl: String, body: String): Request =
            Request.Builder()
                .url("$baseUrl/v1/systemone")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

        internal fun parse(body: String, latencyMs: Long): Result.Ok? {
            val root = MiniJson.parse(body) as? Map<*, *> ?: return null
            val raw = root["answers"] as? Map<*, *> ?: return null
            val answers = LinkedHashMap<String, Answer>()
            for ((k, v) in raw) {
                val key = k as? String ?: return null
                val a = v as? Map<*, *> ?: return null
                when (a["type"]) {
                    "choice" -> {
                        val probsRaw = a["probabilities"] as? Map<*, *> ?: return null
                        val probs = LinkedHashMap<String, Double>()
                        for ((pk, pv) in probsRaw) probs[pk as? String ?: return null] = num(pv) ?: return null
                        val choice = a["choice"] as? String ?: return null
                        answers[key] = Answer.Choice(choice, probs, num(a["confidence"]) ?: return null)
                    }
                    "noul" -> answers[key] = Answer.Noul(num(a["noul"]) ?: return null)
                    else -> {} // Score 等本项目不问的类型：忽略
                }
            }
            val usage = root["usage"] as? Map<*, *>
            return Result.Ok(answers, root["model"] as? String ?: "", (usage?.get("input_tokens") as? Number)?.toInt() ?: 0, latencyMs)
        }

        private fun num(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { !it.isNaN() }
    }

    private class OkHttpTransport(private val apiKey: String, private val baseUrl: String) : (String) -> HttpResult {
        private val http = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        override fun invoke(requestBody: String): HttpResult =
            http.newCall(buildHttpRequest(apiKey, baseUrl, requestBody)).execute().use { resp ->
                HttpResult(resp.code, resp.body?.string().orEmpty(), resp.header("Retry-After")?.trim()?.toDoubleOrNull())
            }
    }

    /** [stateJson] 与 [questionsJson] 必须已是合法 JSON（由 [TargetLocator] 生成）。 */
    fun evaluate(stateJson: String, questionsJson: String): Result {
        if (apiKey.isBlank()) return Result.Err("未配置 typesafe.apiKey，请检查 local.properties")
        val body = """{"model":${PromptBuilder.jsonString(model)},"state":$stateJson,"questions":$questionsJson}"""
        val t0 = System.currentTimeMillis()
        var lastError = ""
        for (attempt in 0..1) {
            val r = try {
                transport(body)
            } catch (e: Exception) {
                lastError = "请求异常: ${e.javaClass.simpleName}: ${e.message}"
                if (attempt == 0) { sleeper(DEFAULT_RETRY_WAIT_MS); continue }
                break
            }
            if (r.code in 200..299) {
                return parse(r.body, System.currentTimeMillis() - t0) ?: Result.Err("响应无法解析: ${r.body.take(200)}")
            }
            lastError = "HTTP ${r.code}: ${r.body.take(200)}"
            if (r.code != 429 && r.code < 500) return Result.Err(lastError)
            if (attempt == 0) sleeper(retryWaitMs(r.retryAfterSeconds))
        }
        return Result.Err("$lastError（已重试 1 次）")
    }
}
