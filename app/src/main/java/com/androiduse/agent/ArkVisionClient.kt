package com.androiduse.agent

import com.androiduse.actuation.Action
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 调用火山方舟 Agent Plan 订阅套餐的 OpenAI 兼容接口。
 *
 * baseUrl 必须是 .../api/plan/v3（订阅通道），不是 .../api/v3（按量计费通道）——
 * 后者会产生额外费用且订阅 key 在那里鉴权失败。model 统一传 ark-code-latest，
 * 实际使用哪个模型由方舟控制台的「使用配置」决定。
 *
 * 只做 HTTP，不含解析逻辑——解析在 ResponseParser 里，那部分可单测。
 */
class ArkVisionClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 返回 (解析出的动作, 原始正文)。动作为 null 表示解析失败，正文用于日志排查。 */
    fun decideNextAction(task: String, history: List<String>, jpegBase64: String): Pair<Action?, String> {
        if (apiKey.isBlank() || model.isBlank()) {
            return null to "未配置 ark.apiKey / ark.modelId，请检查 local.properties"
        }
        val body = PromptBuilder.buildRequestBody(model, task, history, jpegBase64)
        val req = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return null to "HTTP ${resp.code}: ${text.take(300)}"
                val content = ResponseParser.extractContent(text)
                    ?: return null to "响应里没有 content: ${text.take(300)}"
                ResponseParser.parseAction(content) to content
            }
        } catch (e: Exception) {
            null to "请求异常: ${e.message}"
        }
    }
}
