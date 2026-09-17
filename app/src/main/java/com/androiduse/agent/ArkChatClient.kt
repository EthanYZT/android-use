package com.androiduse.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 火山方舟 Agent Plan 订阅套餐的 OpenAI 兼容聊天客户端。**纯传输**：请求体进，
 * [ResponseParser.Completion] 出。决策逻辑全部在 [AgentLoop]，请求体由 [PromptBuilder] 生成。
 *
 * baseUrl 必须是 .../api/plan/v3（订阅通道），不是 .../api/v3（按量计费通道）——
 * 后者会产生额外费用且订阅 key 在那里鉴权失败。model 写在请求体里。
 */
class ArkChatClient(
    private val apiKey: String,
    private val baseUrl: String,
    /** 传输层：发请求体，回状态码+正文。默认 OkHttp；单测注入假实现测重试逻辑。 */
    private val transport: (requestBody: String) -> HttpResult = OkHttpTransport(apiKey, baseUrl),
) {
    data class HttpResult(val code: Int, val body: String)

    sealed class Result {
        data class Ok(val completion: ResponseParser.Completion, val latencyMs: Long) : Result()
        data class Err(val message: String) : Result()
    }

    companion object {
        /**
         * 读超时要和 PromptBuilder.MAX_TOKENS 配套：实测生成速度约 48 token/s，
         * 8192 token 的回复要跑约 170s。之前 60s 只够约 2900 token，上限放大后若不跟着改，
         * 长回复会从"被截断"变成"请求异常: timeout"，换个错误而已。
         */
        const val READ_TIMEOUT_SECONDS = 180L
    }

    private class OkHttpTransport(private val apiKey: String, private val baseUrl: String) :
        (String) -> HttpResult {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        override fun invoke(requestBody: String): HttpResult {
            val req = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            return http.newCall(req).execute().use { resp ->
                HttpResult(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    /**
     * 发一次请求。推理模型偶发把整个 max_tokens 烧在思考上（finish_reason=length，content 为空）：
     * 最坏场景 37 次实测里 90% 在 1000 token 以内，所以截断后重试一次基本就能过，比一味放大
     * 上限便宜。两次都截断才放弃，错误文本带 finish_reason / 推理 token 数，让日志能区分
     * "被截断"和"真的解析不出"。
     */
    fun chat(requestBody: String): Result {
        if (apiKey.isBlank()) return Result.Err("未配置 ark.apiKey，请检查 local.properties")
        var last: ResponseParser.Completion? = null
        val t0 = System.currentTimeMillis()
        repeat(2) {
            val r = try {
                transport(requestBody)
            } catch (e: Exception) {
                return Result.Err("请求异常: ${e.message}")
            }
            if (r.code !in 200..299) return Result.Err("HTTP ${r.code}: ${r.body.take(300)}")
            val c = ResponseParser.extractCompletion(r.body)
                ?: return Result.Err("响应里没有 content: ${r.body.take(300)}")
            if (!c.truncated) return Result.Ok(c, System.currentTimeMillis() - t0)
            last = c
        }
        val c = last!!
        return Result.Err(
            "回复被截断（finish_reason=${c.finishReason}，推理 ${c.reasoningTokens ?: "?"} token，" +
                "max_tokens=${PromptBuilder.MAX_TOKENS}，已重试 1 次）: ${c.content.take(100)}"
        )
    }
}
