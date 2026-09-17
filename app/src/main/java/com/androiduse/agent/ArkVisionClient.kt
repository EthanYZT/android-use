package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 调用火山方舟 Agent Plan 订阅套餐的 OpenAI 兼容接口。
 *
 * baseUrl 必须是 .../api/plan/v3（订阅通道），不是 .../api/v3（按量计费通道）——
 * 后者会产生额外费用且订阅 key 在那里鉴权失败。model 由调用方传入，实际配置为
 * BuildConfig.ARK_MODEL_ID（当前是 glm-5.3-flash），来自 local.properties。
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

    /**
     * 返回 (解析出的动作, 原始正文)。动作为 null 表示解析失败，正文用于日志排查。
     *
     * 1c：传入 [nodes]（守护进程读到的节点，可空）+ 屏幕像素尺寸。节点列表进提示词、并用于把
     * 模型选中的 id 解析成坐标。nodes 为空即退化为阶段 0 的「仅截图」。
     */
    fun decideNextAction(
        task: String,
        history: List<String>,
        jpegBase64: String,
        nodes: List<NodeRecord> = emptyList(),
        screenW: Int = 0,
        screenH: Int = 0,
    ): Pair<Action?, String> {
        if (apiKey.isBlank() || model.isBlank()) {
            return null to "未配置 ark.apiKey / ark.modelId，请检查 local.properties"
        }
        val nodesBlock = NodeGrounding.promptBlock(nodes, screenW, screenH)
        val body = PromptBuilder.buildRequestBody(model, task, history, jpegBase64, nodesBlock)
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
                ResponseParser.parseAction(content, nodes, screenW, screenH) to content
            }
        } catch (e: Exception) {
            null to "请求异常: ${e.message}"
        }
    }
}
