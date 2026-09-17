package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord

/**
 * 一次任务的完整记录，**唯一事实源**：发给模型的 messages（[PromptBuilder]）和导出的日志
 * （[TranscriptCodec]）都是它的投影，由纯函数生成，不各自手工拼接。
 *
 * 这就是 DESIGN §6.3 说的"当前任务的 transcript"——运行时状态，生命周期在任务内。
 * 纯 Kotlin，不依赖 Android，可单测、可离线回放。
 */
data class Transcript(
    val taskId: String,
    val task: String,
    val model: String,
    val startedAtMs: Long,
    val screenW: Int,
    val screenH: Int,
    val steps: MutableList<Step> = mutableListOf(),
)

/**
 * 一步 = 一次观察 + 模型对它的一次或多次回复 + 一次执行。
 * 正常一步只有一条回复；模型只给文字没给 tool call 时，AgentLoop 会追问一次，于是有两条。
 */
data class Step(
    val index: Int,
    val observation: Observation,
    val replies: MutableList<ModelReply> = mutableListOf(),
    var execution: Execution? = null,
)

/** 这一步开始时屏幕的样子。base64 只在内存里给最近几步用，落盘只记路径。 */
data class Observation(
    val screenshotBase64: String?,
    val screenshotPath: String?,
    val nodes: List<NodeRecord>,
    val nodesBlock: String,
    val dumpError: String?,
)

/** 模型一次回复：content 是它的观察笔记，tool_calls 是动作。 */
data class ModelReply(
    val note: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val reasoningTokens: Int?,
    val latencyMs: Long,
)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** 对第一条 tool call 的执行结果。action 为 null 表示没解析出动作（result 里说明原因）。 */
data class Execution(
    val action: Action?,
    val ok: Boolean,
    val result: String,
    val costMs: Long,
)

/**
 * Transcript → JSONL。第一行是任务头，之后每步一行。手写编码（JVM 单测里 org.json 是桩）。
 * 不写截图 base64，只写路径——日志要能随手导出，不能几十 MB。
 */
object TranscriptCodec {

    fun encodeHeader(t: Transcript): String = buildString {
        append('{')
        append("\"type\":\"task\"")
        append(",\"taskId\":").append(js(t.taskId))
        append(",\"task\":").append(js(t.task))
        append(",\"model\":").append(js(t.model))
        append(",\"startedAtMs\":").append(t.startedAtMs)
        append(",\"screenW\":").append(t.screenW)
        append(",\"screenH\":").append(t.screenH)
        append('}')
    }

    fun encodeStep(s: Step): String = buildString {
        append('{')
        append("\"type\":\"step\"")
        append(",\"index\":").append(s.index)
        append(",\"observation\":").append(encodeObservation(s.observation))
        append(",\"replies\":[")
        s.replies.forEachIndexed { i, r -> if (i > 0) append(','); append(encodeReply(r)) }
        append(']')
        s.execution?.let { append(",\"execution\":").append(encodeExecution(it)) }
        append('}')
    }

    private fun encodeObservation(o: Observation): String = buildString {
        append('{')
        append("\"screenshotPath\":").append(o.screenshotPath?.let { js(it) } ?: "null")
        append(",\"nodeCount\":").append(o.nodes.size)
        append(",\"nodesBlock\":").append(js(o.nodesBlock))
        append(",\"dumpError\":").append(o.dumpError?.let { js(it) } ?: "null")
        append('}')
    }

    private fun encodeReply(r: ModelReply): String = buildString {
        append('{')
        append("\"note\":").append(js(r.note))
        append(",\"toolCalls\":[")
        r.toolCalls.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("{\"id\":").append(js(c.id))
                .append(",\"name\":").append(js(c.name))
                .append(",\"arguments\":").append(js(c.argumentsJson)).append('}')
        }
        append(']')
        append(",\"finishReason\":").append(r.finishReason?.let { js(it) } ?: "null")
        append(",\"reasoningTokens\":").append(r.reasoningTokens?.toString() ?: "null")
        append(",\"latencyMs\":").append(r.latencyMs)
        append('}')
    }

    private fun encodeExecution(e: Execution): String = buildString {
        append('{')
        append("\"action\":").append(e.action?.let { js(it.toString()) } ?: "null")
        append(",\"ok\":").append(e.ok)
        append(",\"result\":").append(js(e.result))
        append(",\"costMs\":").append(e.costMs)
        append('}')
    }

    private fun js(s: String) = PromptBuilder.jsonString(s)
}
