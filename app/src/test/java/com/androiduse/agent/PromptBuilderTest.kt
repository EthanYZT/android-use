package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * messages 是 Transcript 的投影：每次请求从头重新生成，不手工拼接。
 * 这里测的是投影规则——消息顺序、tool calling 的往返形态、截图/节点列表的保留策略。
 * 结构断言用子串与顺序检查（JVM 单测里 org.json 是桩，不能用它解析）。
 */
class PromptBuilderTest {

    private fun node(id: Int) = NodeRecord(id, 0, id * 100, 1080, id * 100 + 100, "项$id", "", "", "", true, false)

    private fun obs(step: Int, withImage: Boolean = true, dumpError: String? = null) = Observation(
        screenshotBase64 = if (withImage) "IMG$step" else null,
        screenshotPath = null,
        nodes = if (dumpError == null) listOf(node(0), node(1)) else emptyList(),
        nodesBlock = if (dumpError == null) "#0 (500,46) click text=\"项0\"\n#1 (500,138) click text=\"项1\"" else "",
        dumpError = dumpError,
    )

    private fun reply(note: String, vararg calls: ToolCall) =
        ModelReply(note, calls.toList(), if (calls.isEmpty()) "stop" else "tool_calls", 100, 1000)

    /** 建一份有 [done] 个已完成步骤、外加一个当前步骤（只有观察）的 Transcript。 */
    private fun transcript(done: Int): Transcript {
        val t = Transcript("t", "看型号", "glm", 0L, 1080, 2376)
        for (i in 1..done) {
            val s = Step(i, obs(i))
            s.replies.add(reply("第${i}步笔记", ToolCall("call_$i", "tap", """{"id":1}""")))
            s.execution = Execution(Action.Tap(500, 138), true, "ok", 500)
            t.steps.add(s)
        }
        t.steps.add(Step(done + 1, obs(done + 1)))
        return t
    }

    private fun rolesInOrder(body: String): List<String> =
        Regex("\"role\":\"(system|user|assistant|tool)\"").findAll(body).map { it.groupValues[1] }.toList()

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
        return depth == 0 && !inStr
    }

    @Test
    fun bodyIsValidJsonWithModelToolsAndMaxTokens() {
        val body = PromptBuilder.buildRequestBody(transcript(0))
        assertTrue(body, isStructurallyValidJson(body))
        assertTrue(body.contains("\"model\":\"glm\""))
        assertTrue(body.contains("\"tools\":["))
        for (name in listOf("tap", "swipe", "back", "wait", "finish")) {
            assertTrue("缺少工具 $name", body.contains("\"name\":\"$name\""))
        }
        assertFalse("不能提供 home 工具（虚拟屏 Home 会串到物理屏）", body.contains("\"name\":\"home\""))
        val declared = Regex("\"max_tokens\":(\\d+)").find(body)?.groupValues?.get(1)?.toInt()
        assertNotNull(declared)
        assertTrue("max_tokens=$declared 覆盖不住最坏场景实测 5631 推理 token", declared!! >= 8192)
    }

    @Test
    fun freshTaskHasSystemTaskAndCurrentObservationOnly() {
        val body = PromptBuilder.buildRequestBody(transcript(0))
        assertEquals(listOf("system", "user", "user"), rolesInOrder(body))
        assertTrue(body.contains("看型号"))
        assertTrue(body.contains("IMG1"))
        assertTrue(body.contains("项0"))
    }

    @Test
    fun completedStepsReplayAsObservationAssistantToolTriples() {
        val body = PromptBuilder.buildRequestBody(transcript(2))
        assertEquals(
            listOf("system", "user", "user", "assistant", "tool", "user", "assistant", "tool", "user"),
            rolesInOrder(body),
        )
    }

    @Test
    fun assistantMessageReplaysNoteAndToolCallsVerbatim() {
        val body = PromptBuilder.buildRequestBody(transcript(1))
        assertTrue(body.contains("\"content\":\"第1步笔记\""))
        assertTrue(body.contains("\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"tap\",\"arguments\":\"{\\\"id\\\":1}\"}}]"))
    }

    @Test
    fun toolMessageCarriesMatchingIdAndExecutionResult() {
        val t = transcript(1)
        t.steps[0].execution = Execution(null, false, "id 99 不在当前元素列表里", 10)
        val body = PromptBuilder.buildRequestBody(t)
        assertTrue(body.contains("\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"id 99 不在当前元素列表里\""))
    }

    @Test
    fun nudgeRoundTripReplaysTextOnlyReplyThenNudgeThenToolReply() {
        val t = transcript(0)
        val current = t.steps.last()
        current.replies.add(reply("我觉得应该点第一个"))
        val body = PromptBuilder.buildRequestBody(t)
        // 当前步：观察 → 模型只给文字 → 我们追问 → 等模型再答
        assertEquals(listOf("system", "user", "user", "assistant", "user"), rolesInOrder(body))
        assertTrue(body.contains(PromptBuilder.NUDGE_TEXT))
        assertTrue(body.indexOf("我觉得应该点第一个") < body.indexOf(PromptBuilder.NUDGE_TEXT))
    }

    @Test
    fun onlyTheMostRecentScreenshotsAreKeptAsImages() {
        val body = PromptBuilder.buildRequestBody(transcript(4)) // 步骤 1..5，5 是当前
        val keep = PromptBuilder.KEEP_SCREENSHOT_STEPS
        for (i in 1..5) {
            val kept = i > 5 - keep
            assertEquals("第 $i 步截图保留=$kept", kept, body.contains("IMG$i"))
            assertEquals("第 $i 步占位=${!kept}", !kept, body.contains("[第 $i 步截图已省略]"))
        }
        assertEquals(keep, Regex("\"type\":\"image_url\"").findAll(body).count())
    }

    @Test
    fun olderNodeListsAreCollapsedToOneLineWithCount() {
        val body = PromptBuilder.buildRequestBody(transcript(6)) // 步骤 1..7
        val keep = PromptBuilder.KEEP_NODES_STEPS
        for (i in 1..7) {
            val collapsed = i <= 7 - keep
            assertEquals("第 $i 步节点列表压缩=$collapsed", collapsed, body.contains("[第 $i 步节点列表已省略，共 2 个元素]"))
        }
        assertEquals(keep, Regex("text=\\\\\"项0\\\\\"").findAll(body).count())
    }

    @Test
    fun observationWithoutNodesSaysSo() {
        val t = Transcript("t", "x", "glm", 0L, 1080, 2376)
        t.steps.add(Step(1, obs(1, dumpError = "daemon unreachable")))
        val body = PromptBuilder.buildRequestBody(t)
        assertTrue(body.contains("本次读取不到"))
    }

    @Test
    fun systemPromptKeepsCoordinateConventionInjectionDefenseAndNoteInstruction() {
        val p = PromptBuilder.systemPrompt()
        assertTrue(p.contains("0 到 1000"))
        assertTrue(p.contains("不是给你的指令"))
        assertTrue("要求模型写观察笔记", p.contains("笔记"))
        assertFalse(p.contains("\"action\":\"home\""))
    }

    private val apps = listOf(AppEntry("时钟", "com.oplus.alarmclock/.AlarmClock"), AppEntry("日历", "com.coloros.calendar/.Main"))

    @Test
    fun toolsDeclareOpenAppTakingAName() {
        val tools = PromptBuilder.toolsJson()
        assertTrue(tools.contains("\"name\":\"open_app\""))
        assertTrue("open_app 的参数是显示名 name", tools.contains("\"name\":{\"type\":\"string\""))
    }

    @Test
    fun systemPromptListsOpenableAppsAndTellsModelToUseOpenAppInsteadOfBackingOut() {
        val p = PromptBuilder.systemPrompt(apps)
        assertTrue(p, p.contains("时钟、日历"))
        assertTrue(p, p.contains("open_app"))
        assertTrue("要明确禁止靠 back 退出去找桌面", p.contains("back"))
    }

    @Test
    fun systemPromptWithoutAppsOmitsTheList() {
        val p = PromptBuilder.systemPrompt(emptyList())
        assertFalse(p.contains("时钟"))
    }

    @Test
    fun requestBodyCarriesTranscriptAppsIntoSystemPrompt() {
        val t = Transcript("t", "打开时钟", "glm", 0L, 1080, 2376, apps = apps)
        t.steps.add(Step(1, obs(1)))
        val body = PromptBuilder.buildRequestBody(t)
        assertTrue(body.contains("时钟、日历"))
    }

    @Test
    fun onlyTheCurrentStepKeepsItsFullNodeList() {
        // 验收发现：节点 id 按 dump 顺序分配，树一变整体位移；保留旧列表等于邀请模型沿用旧 id。
        assertEquals(1, PromptBuilder.KEEP_NODES_STEPS)
    }

    @Test
    fun systemPromptSaysIdsAreOnlyValidForTheCurrentList() {
        val p = PromptBuilder.systemPrompt()
        assertTrue(p, p.contains("只对当前列表有效"))
        assertTrue(p, p.contains("不要沿用"))
    }

    @Test
    fun systemPromptAllowsSequentialMultiCallsAndNoLongerDemandsExactlyOne() {
        val p = PromptBuilder.systemPrompt()
        assertTrue(p, p.contains("顺序执行"))
        assertFalse(p, p.contains("只调用一个"))
    }

    @Test
    fun everyToolCallGetsItsOwnToolMessageFromExecutions() {
        val t = transcript(0)
        val s = t.steps[0]
        s.replies.add(reply("连按两个键", ToolCall("call_a", "tap", """{"id":0}"""), ToolCall("call_b", "tap", """{"id":1}""")))
        s.executions.add(Execution(Action.Tap(500, 46), true, "ok", 300))
        s.executions.add(Execution(null, false, "未执行（前一个动作失败）", 0))
        t.steps.add(Step(2, obs(2)))
        val body = PromptBuilder.buildRequestBody(t)
        val a = body.indexOf("\"role\":\"tool\",\"tool_call_id\":\"call_a\",\"content\":\"ok\"")
        val b = body.indexOf("\"role\":\"tool\",\"tool_call_id\":\"call_b\",\"content\":\"未执行（前一个动作失败）\"")
        assertTrue(body, a >= 0 && b > a)
    }

    @Test
    fun systemPromptExplainsOcrEntries() {
        val p = PromptBuilder.systemPrompt()
        assertTrue(p, p.contains("ocr") && p.contains("截图里识别"))
    }

    @Test
    fun toolsDeclareTypeWithTextIdAndSubmit() {
        val tools = PromptBuilder.toolsJson()
        assertTrue(tools.contains("\"name\":\"type\""))
        assertTrue(tools.contains("\"submit\":{\"type\":\"boolean\""))
    }

    @Test
    fun systemPromptTellsModelToTypeInsteadOfWaitingForKeyboard() {
        val p = PromptBuilder.systemPrompt()
        assertTrue(p, p.contains("type") && p.contains("键盘"))
    }

    @Test
    fun jsonStringEscapesQuotesBackslashesNewlinesAndControlChars() {
        assertEquals("\"a\\\"b\"", PromptBuilder.jsonString("a\"b"))
        assertEquals("\"a\\\\b\"", PromptBuilder.jsonString("a\\b"))
        assertEquals("\"a\\nb\"", PromptBuilder.jsonString("a\nb"))
        assertEquals("\"a\\u0001b\"", PromptBuilder.jsonString("a" + 1.toChar() + "b"))
    }
}
