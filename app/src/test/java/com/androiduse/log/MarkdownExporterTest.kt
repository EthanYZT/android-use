package com.androiduse.log

import com.androiduse.agent.StoredExecution
import com.androiduse.agent.StoredOutcome
import com.androiduse.agent.StoredReply
import com.androiduse.agent.StoredStep
import com.androiduse.agent.StoredTranscript
import com.androiduse.agent.ToolCall
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导出的 Markdown 是给调试者读的：任务、结果、每步的笔记/动作/结果/截图文件名都得在。 */
class MarkdownExporterTest {

    private fun stored() = StoredTranscript(
        taskId = "task-9", task = "看型号", model = "glm-5.3-flash", startedAtMs = 1_700_000_000_000L,
        screenW = 1080, screenH = 2376,
        steps = listOf(
            StoredStep(
                index = 1, screenshotPath = "/d/task-9/step-1.jpg", nodeCount = 36,
                nodesBlock = "#0 (50,50) click text=\"返回\"", dumpError = null,
                replies = listOf(
                    StoredReply("我先想想", emptyList(), "stop", 30, 900),
                    StoredReply("看到关于本机", listOf(ToolCall("c1", "tap", """{"id":7}""")), "tool_calls", 120, 5400),
                ),
                execution = StoredExecution("Tap(xNorm=500, yNorm=50)", true, "ok", 800),
            ),
            StoredStep(
                index = 2, screenshotPath = null, nodeCount = 0, nodesBlock = "", dumpError = "daemon unreachable",
                replies = listOf(StoredReply("完成", listOf(ToolCall("c2", "finish", """{"summary":"型号 一加"}""")), "tool_calls", null, 100)),
                execution = StoredExecution("Finish(summary=型号 一加)", true, "finish", 50),
            ),
        ),
        outcome = StoredOutcome(true, "型号 一加", 1_700_000_050_000L),
    )

    @Test
    fun rendersTaskOutcomeAndEveryStep() {
        val md = MarkdownExporter.render(stored())
        assertTrue(md, md.startsWith("# "))
        assertTrue(md.contains("看型号"))
        assertTrue(md.contains("glm-5.3-flash"))
        assertTrue(md.contains("型号 一加"))
        assertTrue(md.contains("## 第 1 步"))
        assertTrue(md.contains("## 第 2 步"))
        assertTrue(md.contains("我先想想"))
        assertTrue(md.contains("看到关于本机"))
        assertTrue(md.contains("""tap {"id":7}"""))
        assertTrue(md.contains("ok"))
        assertTrue(md.contains("step-1.jpg"))
        assertTrue(md.contains("daemon unreachable"))
        assertTrue("推理 token 与耗时要在", md.contains("120") && md.contains("5400"))
        assertTrue("节点列表放在折叠块里", md.contains("<details>") && md.contains("返回"))
    }

    @Test
    fun unfinishedTranscriptSaysSo() {
        val md = MarkdownExporter.render(stored().copy(outcome = null))
        assertTrue(md, md.contains("未结束"))
    }
}
