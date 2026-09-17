package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcript 是唯一事实源；JSONL 是它给日志导出（子项目 B）的投影。
 * 每步一行，必须是合法 JSON，且不把截图 base64 写进去（只写路径）。
 */
class TranscriptCodecTest {

    private fun node(id: Int, text: String) =
        NodeRecord(id, 0, id * 100, 1080, id * 100 + 100, text, "", "", "android.widget.TextView", true, false)

    private fun sampleStep(): Step {
        val obs = Observation(
            screenshotBase64 = "ZmFrZQ==",
            screenshotPath = "/data/x/step-1.jpg",
            nodes = listOf(node(0, "返回"), node(1, "关于\"本机\"")),
            nodesBlock = "#0 (500,46) click text=\"返回\"\n#1 (500,138) click text=\"关于\\\"本机\\\"\"",
            dumpError = null,
        )
        val step = Step(index = 1, observation = obs)
        step.replies.add(
            ModelReply(
                note = "看到\"关于本机\"入口",
                toolCalls = listOf(ToolCall("call_1", "tap", """{"id":1}""")),
                finishReason = "tool_calls",
                reasoningTokens = 120,
                latencyMs = 5400,
            )
        )
        step.execution = Execution(Action.Tap(500, 138), ok = true, result = "ok", costMs = 800)
        return step
    }

    @Test
    fun headerLineIsValidJsonWithTaskFields() {
        val t = Transcript("t1", "看型号", "glm-5.3-flash", 1_700_000_000_000L, 1080, 2376)
        val line = TranscriptCodec.encodeHeader(t)
        assertTrue(line, isStructurallyValidJson(line))
        assertTrue(line.contains("\"type\":\"task\""))
        assertTrue(line.contains("\"task\":\"看型号\""))
        assertTrue(line.contains("\"screenW\":1080"))
    }

    @Test
    fun stepLineIsValidJsonAndCarriesNoteToolCallAndResult() {
        val line = TranscriptCodec.encodeStep(sampleStep())
        assertTrue(line, isStructurallyValidJson(line))
        assertFalse("每步一行，不能带换行", line.contains('\n'))
        assertTrue(line.contains("\"type\":\"step\""))
        assertTrue(line.contains("\"index\":1"))
        assertTrue(line.contains("看到"))
        assertTrue(line.contains("\"name\":\"tap\""))
        assertTrue(line.contains("\"finishReason\":\"tool_calls\""))
        assertTrue(line.contains("\"reasoningTokens\":120"))
        assertTrue(line.contains("\"ok\":true"))
        assertTrue(line.contains("\"result\":\"ok\""))
        assertTrue(line.contains("\"nodeCount\":2"))
    }

    @Test
    fun stepLineWritesScreenshotPathButNeverBase64() {
        val line = TranscriptCodec.encodeStep(sampleStep())
        assertTrue(line.contains("/data/x/step-1.jpg"))
        assertFalse("截图 base64 不能进日志", line.contains("ZmFrZQ=="))
    }

    @Test
    fun stepLineEscapesQuotesAndNewlinesInsideValues() {
        val line = TranscriptCodec.encodeStep(sampleStep())
        assertTrue(isStructurallyValidJson(line))
        // 节点文本里的引号与 nodesBlock 里的换行都必须被转义，而不是原样写出破坏 JSON。
        assertTrue(line.contains("\\n"))
        // nodesBlock 里原本就是 `关于\"本机\"`（UntrustedText 已转义一层），JSON 再转义一层。
        assertTrue(line, line.contains("关于\\\\\\\"本机"))
    }

    @Test
    fun stepWithoutReplyOrExecutionStillEncodes() {
        val step = Step(2, Observation(null, null, emptyList(), "", dumpError = "daemon unreachable"))
        val line = TranscriptCodec.encodeStep(step)
        assertTrue(line, isStructurallyValidJson(line))
        assertTrue(line.contains("daemon unreachable"))
        assertTrue(line.contains("\"replies\":[]"))
        assertEquals(-1, line.indexOf("\"execution\":{"))
    }

    /** 括号配平 + 字符串内忽略，够用来抓"没转义"这类破坏结构的问题。 */
    private fun isStructurallyValidJson(s: String): Boolean {
        var depth = 0; var inStr = false; var esc = false
        for (c in s) {
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> {}
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return depth == 0 && !inStr && s.trim().startsWith("{") && s.trim().endsWith("}")
    }
}
