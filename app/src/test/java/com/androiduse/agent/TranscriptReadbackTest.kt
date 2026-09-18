package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 写出去的 JSONL 必须能原样读回来——这是日志查看页和 Markdown 导出的数据源。 */
class TranscriptReadbackTest {

    private fun node(id: Int) = NodeRecord(id, 0, 0, 100, 100, "项$id", "", "", "", true, false)

    private fun sample(): Transcript {
        val t = Transcript("task-9", "看型号\n再改超时", "glm-5.3-flash", 1_700_000_000_000L, 1080, 2376)
        val s1 = Step(1, Observation("IMG", "/d/step-1.jpg", listOf(node(0), node(1)), "#0 (50,50) click text=\"项0\"", null))
        s1.replies.add(ModelReply("我先想想", emptyList(), "stop", 30, 900))
        s1.replies.add(ModelReply("看到\"关于本机\"", listOf(ToolCall("c1", "tap", """{"id":1}""")), "tool_calls", 120, 5400))
        s1.execution = Execution(Action.Tap(500, 50), true, "ok", 800)
        t.steps.add(s1)
        val s2 = Step(2, Observation(null, null, emptyList(), "", "daemon unreachable"))
        s2.replies.add(ModelReply("完成", listOf(ToolCall("c2", "finish", """{"summary":"型号 一加"}""")), "tool_calls", null, 100))
        s2.execution = Execution(Action.Finish("型号 一加"), true, "finish", 50)
        t.steps.add(s2)
        return t
    }

    private fun lines(t: Transcript, withOutcome: Boolean): List<String> {
        val out = mutableListOf(TranscriptCodec.encodeHeader(t))
        t.steps.forEach { out += TranscriptCodec.encodeStep(it) }
        if (withOutcome) out += TranscriptCodec.encodeOutcome(finished = true, summary = "型号 一加", endedAtMs = 1_700_000_050_000L)
        return out
    }

    @Test
    fun decodesHeaderStepsRepliesAndExecution() {
        val s = TranscriptCodec.decode(lines(sample(), withOutcome = true))!!
        assertEquals("task-9", s.taskId)
        assertEquals("看型号\n再改超时", s.task)
        assertEquals("glm-5.3-flash", s.model)
        assertEquals(1_700_000_000_000L, s.startedAtMs)
        assertEquals(1080, s.screenW)
        assertEquals(2, s.steps.size)

        val st1 = s.steps[0]
        assertEquals(1, st1.index)
        assertEquals("/d/step-1.jpg", st1.screenshotPath)
        assertEquals(2, st1.nodeCount)
        assertEquals("#0 (50,50) click text=\"项0\"", st1.nodesBlock)
        assertNull(st1.dumpError)
        assertEquals(2, st1.replies.size)
        assertEquals("我先想想", st1.replies[0].note)
        assertEquals(emptyList<ToolCall>(), st1.replies[0].toolCalls)
        assertEquals("看到\"关于本机\"", st1.replies[1].note)
        assertEquals(listOf(ToolCall("c1", "tap", """{"id":1}""")), st1.replies[1].toolCalls)
        assertEquals(120, st1.replies[1].reasoningTokens)
        assertEquals(5400L, st1.replies[1].latencyMs)
        assertEquals("Tap(xNorm=500, yNorm=50)", st1.execution!!.action)
        assertTrue(st1.execution!!.ok)
        assertEquals("ok", st1.execution!!.result)
        assertEquals(800L, st1.execution!!.costMs)

        val st2 = s.steps[1]
        assertNull(st2.screenshotPath)
        assertEquals("daemon unreachable", st2.dumpError)
        assertNull(st2.replies[0].reasoningTokens)

        assertEquals(true, s.outcome!!.finished)
        assertEquals("型号 一加", s.outcome!!.summary)
        assertEquals(1_700_000_050_000L, s.outcome!!.endedAtMs)
    }

    @Test
    fun executionsArrayRoundTrips() {
        val t = sample()
        val s1 = t.steps[0]
        s1.executions.add(Execution(Action.Tap(500, 50), true, "ok", 800))
        s1.executions.add(Execution(null, false, "未执行（前一个动作失败）", 0))
        val st = TranscriptCodec.decode(lines(t, withOutcome = false))!!.steps[0]
        assertEquals(2, st.executions.size)
        assertEquals("Tap(xNorm=500, yNorm=50)", st.executions[0].action)
        assertTrue(st.executions[0].ok)
        assertFalse(st.executions[1].ok)
        assertEquals("未执行（前一个动作失败）", st.executions[1].result)
        assertEquals(st, t.steps[0].toStored())
    }

    @Test
    fun ocrCountRoundTrips() {
        val t = sample()
        val s = Step(3, Observation("IMG", null, emptyList(), "", null, ocrCount = 2))
        t.steps.add(s)
        val st = TranscriptCodec.decode(lines(t, withOutcome = false))!!.steps[2]
        assertEquals(2, st.ocrCount)
        assertEquals(st, s.toStored())
    }

    @Test
    fun legacyStepLineWithoutExecutionsDecodesToEmptyList() {
        val st = TranscriptCodec.decode(lines(sample(), withOutcome = false))!!.steps[0]
        assertTrue(st.executions.isEmpty())
        assertEquals("ok", st.execution!!.result)
    }

    @Test
    fun transcriptWithoutOutcomeLineDecodesWithNullOutcome() {
        val s = TranscriptCodec.decode(lines(sample(), withOutcome = false))!!
        assertNull(s.outcome)
        assertEquals(2, s.steps.size)
    }

    @Test
    fun headerOnlyAndGarbageLinesAreHandled() {
        val t = sample()
        assertEquals(0, TranscriptCodec.decode(listOf(TranscriptCodec.encodeHeader(t)))!!.steps.size)
        assertNull(TranscriptCodec.decode(emptyList()))
        assertNull(TranscriptCodec.decode(listOf("not json")))
        // 中途崩溃留下的半行不应让整份日志读不出来
        val s = TranscriptCodec.decode(lines(t, false) + listOf("""{"type":"step","index":3,"obs"""))!!
        assertEquals(2, s.steps.size)
    }

    @Test
    fun outcomeHandoffFlagRoundTripsAndDefaultsToFalse() {
        val withFlag = listOf(TranscriptCodec.encodeHeader(sample())) + TranscriptCodec.encodeOutcome(finished = false, summary = "停在结算页", endedAtMs = 1L, handoff = true)
        assertTrue(TranscriptCodec.decode(withFlag)!!.outcome!!.handoff)
        val legacy = listOf(TranscriptCodec.encodeHeader(sample())) + """{"type":"outcome","finished":true,"summary":"x","endedAtMs":1}"""
        val o = TranscriptCodec.decode(legacy)!!.outcome!!
        assertFalse(o.handoff)
        assertTrue(o.finished)
    }

    @Test
    fun liveViewEqualsWhatIsReadBackFromDisk() {
        // 实时 UI 用 Step.toStored()，历史页用 decode()；两条路必须给出同一份视图。
        val t = sample()
        val decoded = TranscriptCodec.decode(lines(t, withOutcome = false))!!
        assertEquals(t.steps.map { it.toStored() }, decoded.steps)
    }
}
