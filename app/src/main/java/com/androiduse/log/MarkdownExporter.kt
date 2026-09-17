package com.androiduse.log

import com.androiduse.agent.StoredTranscript
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把一份任务日志渲染成给人读的 Markdown：任务、结果、每步的笔记/动作/结果/耗时，
 * 节点列表放折叠块里，截图只写文件名（分享单个 .md 时带不了图）。
 */
object MarkdownExporter {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun render(t: StoredTranscript): String = buildString {
        append("# 任务日志：").append(t.task.replace('\n', ' ')).append("\n\n")
        append("- 任务 id：`").append(t.taskId).append("`\n")
        append("- 模型：").append(t.model).append('\n')
        append("- 开始：").append(fmt.format(Date(t.startedAtMs))).append('\n')
        append("- 虚拟屏：").append(t.screenW).append('x').append(t.screenH).append('\n')
        append("- 步数：").append(t.steps.size).append('\n')
        val o = t.outcome
        if (o == null) {
            append("- 结果：**未结束**（日志没有结束行，任务可能中途崩溃或被杀）\n")
        } else {
            append("- 结果：").append(if (o.finished) "**完成**" else "**中止**")
                .append("，").append(fmt.format(Date(o.endedAtMs))).append('\n')
            append("\n> ").append(o.summary.replace("\n", "\n> ")).append('\n')
        }
        append('\n')

        for (s in t.steps) {
            append("## 第 ").append(s.index).append(" 步\n\n")
            append("- 节点：").append(s.nodeCount).append(" 个")
            s.dumpError?.let { append("（读取失败：").append(it).append('）') }
            append('\n')
            s.screenshotPath?.let { append("- 截图：`").append(it.substringAfterLast('/')).append("`\n") }
            for ((i, r) in s.replies.withIndex()) {
                if (s.replies.size > 1) append("- 回复 ").append(i + 1).append("：\n  ")
                else append("- ")
                append("笔记：").append(r.note.replace('\n', ' ')).append('\n')
                if (r.toolCalls.isNotEmpty()) {
                    append(if (s.replies.size > 1) "  " else "").append("- 动作：`")
                    append(r.toolCalls.joinToString("; ") { "${it.name} ${it.argumentsJson}" }).append("`\n")
                } else {
                    append(if (s.replies.size > 1) "  " else "").append("- 动作：（没有调用工具）\n")
                }
                append(if (s.replies.size > 1) "  " else "").append("- 模型：finish_reason=").append(r.finishReason ?: "?")
                    .append("，推理 ").append(r.reasoningTokens?.toString() ?: "?").append(" token，")
                    .append(r.latencyMs).append(" ms\n")
            }
            s.execution?.let { e ->
                append("- 执行：").append(if (e.ok) "✅ " else "❌ ").append(e.result.replace('\n', ' '))
                e.action?.let { append("（").append(it).append('）') }
                append("，").append(e.costMs).append(" ms\n")
            }
            if (s.nodesBlock.isNotEmpty()) {
                append("\n<details><summary>元素列表</summary>\n\n```\n").append(s.nodesBlock).append("\n```\n\n</details>\n")
            }
            append('\n')
        }
    }
}
