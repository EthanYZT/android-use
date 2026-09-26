package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 循环的决策逻辑：错误反馈给模型而不是中止、追问一次、连续失败/卡住才中止。
 * 环境（截图/节点树/注入）和传输层都用假实现；模型回复用真实的接口 JSON 形态喂进去，
 * 走真正的 ResponseParser / PromptBuilder / ToolCallResolver。
 */
class AgentLoopTest {

    private fun node(id: Int, text: String) =
        NodeRecord(id, 0, id * 100, 1000, id * 100 + 100, text, "", "", "", true, false)

    private fun toolReply(note: String, vararg calls: Pair<String, String>): String {
        val tc = calls.mapIndexed { i, (name, args) ->
            """{"id":"call_$i","type":"function","function":{"name":"$name","arguments":${PromptBuilder.jsonString(args)}}}"""
        }.joinToString(",")
        return """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":${PromptBuilder.jsonString(note)},"tool_calls":[$tc]}}],"usage":{"completion_tokens":50,"completion_tokens_details":{"reasoning_tokens":40}}}"""
    }

    private fun textReply(note: String) =
        """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":${PromptBuilder.jsonString(note)}}}]}"""

    private class FakeEnv(
        private val nodesPerStep: (Int) -> List<NodeRecord>,
        private val performOk: Boolean = true,
        private val apps: List<AppEntry> = emptyList(),
        /** 批内动作之间的重新 dump；null 表示环境不支持刷新。 */
        private val refresh: (() -> List<NodeRecord>?)? = null,
        /** 2a：系统接口调用的假实现；null 表示用默认"不支持"。 */
        private val system: ((com.androiduse.capability.SystemCall) -> SystemResult)? = null,
    ) : Environment {
        override val screenW = 1000
        override val screenH = 2000
        val performed = mutableListOf<Action>()
        var refreshCount = 0
        var lastError: String? = null
        override fun lastError(): String? = lastError
        override fun installedApps(): List<AppEntry> = apps
        override fun refreshNodes(): List<NodeRecord>? { refreshCount++; return refresh?.invoke() }
        var ocrCount = 0
        override fun observe(stepIndex: Int): Observation {
            val nodes = nodesPerStep(stepIndex)
            return Observation("IMG$stepIndex", null, nodes, NodeGrounding.promptBlock(nodes, screenW, screenH), null, ocrCount = ocrCount)
        }
        override fun perform(action: Action): Boolean { performed.add(action); return performOk }
        val systemCalls = mutableListOf<com.androiduse.capability.SystemCall>()
        override fun performSystem(call: com.androiduse.capability.SystemCall): SystemResult {
            systemCalls.add(call)
            return system?.invoke(call) ?: super.performSystem(call)
        }
    }

    private class FakeSink : TranscriptSink {
        var started: Transcript? = null
        val steps = mutableListOf<Int>()
        var outcomeHandoff: Boolean? = null
        override fun start(t: Transcript) { started = t }
        override fun step(t: Transcript, step: Step) { steps.add(step.index) }
        val savedNodes = mutableListOf<Pair<Int, List<NodeRecord>>>()
        var savedOcrLines: List<OcrLine>? = null
        override fun saveNodes(t: Transcript, stepIndex: Int, nodes: List<NodeRecord>, ocrLines: List<OcrLine>?): String? {
            savedNodes += stepIndex to nodes; savedOcrLines = ocrLines; return "/dev/null/step-$stepIndex.nodes.json"
        }
        override fun outcome(t: Transcript, finished: Boolean, summary: String, handoff: Boolean) { outcomeHandoff = handoff }
    }

    private fun harness(
        vararg responses: String,
        env: FakeEnv = FakeEnv({ listOf(node(0, "返回"), node(7, "关于本机")) }),
        maxSteps: Int = 15,
        sink: FakeSink = FakeSink(),
        grounder: Grounder? = null,
    ): Triple<AgentLoop.Outcome, FakeEnv, List<String>> {
        val sent = mutableListOf<String>()
        val queue = responses.toMutableList()
        val client = ArkChatClient("k", "u", transport = { body -> sent.add(body); ArkChatClient.HttpResult(200, queue.removeAt(0)) })
        val loop = AgentLoop(client, env, "glm", sink, grounder)
        val outcome = runBlocking { loop.run("看型号", maxSteps) { } }
        return Triple(outcome, env, sent)
    }

    @Test
    fun finishReturnsSummaryAndTranscriptRecordsEachStep() {
        val (o, env, sent) = harness(
            toolReply("看到关于本机入口", "tap" to """{"id":7}"""),
            toolReply("型号是一加 Ace 5", "finish" to """{"summary":"型号 一加 Ace 5"}"""),
        )
        assertTrue(o.finished)
        assertEquals("型号 一加 Ace 5", o.summary)
        assertEquals(listOf<Action>(Action.Tap(500, 375)), env.performed)
        assertEquals(2, o.transcript.steps.size)
        assertEquals("ok", o.transcript.steps[0].execution!!.result)
        assertEquals("看到关于本机入口", o.transcript.steps[0].replies[0].note)
        // 第二次请求回放了第一步的笔记和 tool 结果——这就是记忆。
        assertTrue(sent[1].contains("看到关于本机入口"))
        assertTrue(sent[1].contains("\"role\":\"tool\""))
        assertEquals(2, sent.size)
    }

    @Test
    fun unknownIdIsFedBackToModelInsteadOfAborting() {
        val (o, env, sent) = harness(
            toolReply("点 99", "tap" to """{"id":99}"""),
            toolReply("改点 7", "tap" to """{"id":7}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
        )
        assertTrue(o.finished)
        val first = o.transcript.steps[0].execution!!
        assertFalse(first.ok)
        assertTrue(first.result, first.result.contains("99"))
        assertTrue("错误要作为 tool 结果回放给模型", sent[1].contains("\"role\":\"tool\"") && sent[1].contains("99"))
        assertEquals(listOf<Action>(Action.Tap(500, 375)), env.performed)
    }

    @Test
    fun textOnlyReplyIsNudgedOnceWithinTheSameStep() {
        val (o, _, sent) = harness(
            textReply("我先想想"),
            toolReply("好，点 7", "tap" to """{"id":7}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
        )
        assertTrue(o.finished)
        assertEquals(2, o.transcript.steps[0].replies.size)
        assertTrue(sent[1].contains(PromptBuilder.NUDGE_TEXT))
        assertEquals(2, o.transcript.steps.size)
    }

    @Test
    fun twoTextOnlyRepliesInOneStepAbort() {
        val (o, env, _) = harness(textReply("嗯"), textReply("嗯嗯"))
        assertFalse(o.finished)
        assertTrue(o.summary, o.summary.contains("工具"))
        assertTrue(env.performed.isEmpty())
    }

    @Test
    fun threeConsecutiveInjectionFailuresAbort() {
        val env = FakeEnv({ listOf(node(7, "x")) }, performOk = false)
        val (o, _, _) = harness(
            toolReply("1", "tap" to """{"id":7}"""),
            toolReply("2", "back" to "{}"),
            toolReply("3", "tap" to """{"x":10,"y":10}"""),
            toolReply("4", "back" to "{}"),
            env = env,
        )
        assertFalse(o.finished)
        assertTrue(o.summary, o.summary.contains("连续"))
        assertEquals(3, env.performed.size)
    }

    @Test
    fun sameScreenAndSameActionThreeTimesIsStuck() {
        val (o, env, _) = harness(
            toolReply("a", "tap" to """{"id":7}"""),
            toolReply("b", "tap" to """{"id":7}"""),
            toolReply("c", "tap" to """{"id":7}"""),
            toolReply("d", "tap" to """{"id":7}"""),
        )
        assertFalse(o.finished)
        assertTrue(o.summary, o.summary.contains("卡住"))
        assertEquals(3, env.performed.size)
    }

    @Test
    fun clientErrorAbortsWithReason() {
        val env = FakeEnv({ listOf(node(7, "x")) })
        val client = ArkChatClient("k", "u", transport = { ArkChatClient.HttpResult(500, "boom") })
        val o = runBlocking { AgentLoop(client, env, "glm", FakeSink()).run("t", 5) { } }
        assertFalse(o.finished)
        assertTrue(o.summary, o.summary.contains("500"))
        assertEquals(1, o.transcript.steps.size)
    }


    @Test
    fun unlimitedStepsRunsPastTheOldCapUntilFinish() {
        // App 不限步数：20 步都在点不同元素（不触发卡住判定），第 21 步 finish，循环不能在 15 步中止。
        val nodes = (0 until 30).map { NodeRecord(it, 0, it * 60, 1000, it * 60 + 50, "项$it", "", "", "", true, false) }
        val replies = (0 until 20).map { toolReply("第 $it 步", "tap" to """{"id":$it}""") } + toolReply("完成", "finish" to """{"summary":"done"}""")
        val (o, env, _) = harness(*replies.toTypedArray(), env = FakeEnv({ nodes }), maxSteps = AgentLoop.UNLIMITED_STEPS)
        assertTrue(o.finished)
        assertEquals(21, o.transcript.steps.size)
        assertEquals(20, env.performed.size)
    }

    @Test
    fun maxStepsAbortMentionsLastNote() {
        val (o, _, _) = harness(
            toolReply("已查到型号 一加 Ace 5", "tap" to """{"id":0}"""),
            toolReply("正在找超时设置", "tap" to """{"id":7}"""),
            maxSteps = 2,
        )
        assertFalse(o.finished)
        assertTrue(o.summary, o.summary.contains("正在找超时设置"))
    }

    @Test
    fun openAppIsResolvedAgainstEnvironmentAppsAndPerformed() {
        val apps = listOf(AppEntry("时钟", "com.oplus.alarmclock/.AlarmClock"))
        val (o, env, sent) = harness(
            toolReply("要去时钟", "open_app" to """{"name":"时钟"}"""),
            toolReply("时钟已打开", "finish" to """{"summary":"done"}"""),
            env = FakeEnv({ listOf(node(7, "x")) }, apps = apps),
        )
        assertTrue(o.finished)
        assertEquals(listOf<Action>(Action.OpenApp("时钟", "com.oplus.alarmclock/.AlarmClock")), env.performed)
        assertEquals("App 列表在任务开始时从 Environment 取一次并记进 Transcript", apps, o.transcript.apps)
        assertTrue("第一次请求的系统提示里就要有 App 列表", sent[0].contains("时钟"))
    }

    @Test
    fun multipleToolCallsExecuteInOrderWithinOneStep() {
        val (o, env, sent) = harness(
            toolReply("连按 7 和 0", "tap" to """{"id":7}""", "tap" to """{"id":0}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
        )
        assertTrue(o.finished)
        assertEquals(listOf<Action>(Action.Tap(500, 375), Action.Tap(500, 25)), env.performed)
        assertEquals(2, o.transcript.steps[0].executions.size)
        assertTrue(o.transcript.steps[0].executions.all { it.ok })
        // 每个 tool call 都要有自己的 tool 消息回放
        assertTrue(sent[1].contains("\"tool_call_id\":\"call_0\"") && sent[1].contains("\"tool_call_id\":\"call_1\""))
        assertEquals(2, o.transcript.steps.size)
    }

    @Test
    fun failureMidSequenceStopsAndReportsTheRestAsNotExecuted() {
        val (o, env, sent) = harness(
            toolReply("按 7、99、0", "tap" to """{"id":7}""", "tap" to """{"id":99}""", "tap" to """{"id":0}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
        )
        assertTrue(o.finished)
        assertEquals(listOf<Action>(Action.Tap(500, 375)), env.performed)
        val ex = o.transcript.steps[0].executions
        assertEquals(3, ex.size)
        assertTrue(ex[0].ok)
        assertFalse(ex[1].ok); assertTrue(ex[1].result, ex[1].result.contains("99"))
        assertFalse(ex[2].ok); assertTrue(ex[2].result, ex[2].result.contains("未执行"))
        assertTrue("未执行也要作为 tool 结果回给模型", sent[1].contains("未执行"))
    }


    @Test
    fun everyStepHandsTheFullNodeListToTheSinkBeforeTheModelSeesIt() {
        // 元素列表在提示词里是筛选过的（NodeGrounding.selectForPrompt）；sink 拿到的必须是整棵树，逐步都有。
        val many = (0 until 120).map { NodeRecord(it, 0, it * 10, 1000, it * 10 + 9, "row$it", "", "", "", true, false) }
        val sink = FakeSink()
        val (o, _, _) = harness(
            toolReply("看到", "tap" to """{"id":7}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
            env = FakeEnv({ many }),
            sink = sink,
        )
        assertTrue(o.finished)
        assertEquals(listOf(1, 2), sink.savedNodes.map { it.first })
        assertEquals(120, sink.savedNodes[0].second.size)
        assertEquals(many, sink.savedNodes[1].second)
    }

    @Test
    fun finishInTheMiddleExecutesPrecedingCallsThenFinishes() {
        val (o, env, _) = harness(
            toolReply("按 7 然后结束", "tap" to """{"id":7}""", "finish" to """{"summary":"done"}""", "tap" to """{"id":0}"""),
        )
        assertTrue(o.finished)
        assertEquals("done", o.summary)
        assertEquals(listOf<Action>(Action.Tap(500, 375)), env.performed)
        assertEquals(3, o.transcript.steps[0].executions.size)
    }

    @Test
    fun handoffEndsTheLoopWithHandoffKindAndReasonAsSummary() {
        val sink = FakeSink()
        val (o, env, _) = harness(
            toolReply("到结算页了", "tap" to """{"id":7}"""),
            toolReply("要付款了", "handoff" to """{"reason":"停在结算页，需要你付款"}"""),
            sink = sink,
        )
        assertFalse(o.finished)
        assertEquals(AgentLoop.Kind.HANDOFF, o.kind)
        assertTrue(o.handoff)
        assertEquals("停在结算页，需要你付款", o.summary)
        assertEquals(2, o.transcript.steps.size)
        assertEquals("handoff", o.transcript.steps[1].execution!!.result)
        // sink 收到的 outcome 也要如实带上 handoff=true（M3：之前只记录不断言）。
        assertEquals(true, sink.outcomeHandoff)
    }

    @Test
    fun callsAfterHandoffInTheSameStepAreNotExecuted() {
        val (o, env, _) = harness(
            toolReply("交接后还想点", "handoff" to """{"reason":"验证码"}""", "tap" to """{"id":7}"""),
        )
        assertEquals(AgentLoop.Kind.HANDOFF, o.kind)
        assertTrue(env.performed.isEmpty())
        assertEquals(AgentLoop.NOT_EXECUTED_AFTER_HANDOFF, o.transcript.steps[0].executions[1].result)
    }

    @Test
    fun finishedAndAbortedOutcomesCarryTheirKind() {
        val finSink = FakeSink()
        val (fin, _, _) = harness(toolReply("完成", "finish" to """{"summary":"done"}"""), sink = finSink)
        assertEquals(AgentLoop.Kind.FINISHED, fin.kind)
        // 正常完成时 sink 收到的 outcome.handoff 要如实为 false（对照上面 handoff 用例的 true）。
        assertEquals(false, finSink.outcomeHandoff)
        val (ab, _, _) = harness(textReply("没有工具"), textReply("还是没有"))
        assertEquals(AgentLoop.Kind.ABORTED, ab.kind)
        assertFalse(ab.handoff)
    }

    @Test
    fun batchedIdTapsAfterTheFirstAreRelocatedInAFreshDump() {
        // 第一次 tap 后界面重排：原 id7 的元素（text "关于本机"）在新树里变成 id 8 且下移。
        val start = listOf(node(7, "关于本机"), node(0, "返回"))
        val shifted = listOf(
            NodeRecord(1, 0, 0, 1000, 100, "新出现的行", "", "", "", false, false),
            NodeRecord(8, 0, 800, 1000, 900, "关于本机", "", "", "", true, false),
            NodeRecord(2, 0, 100, 1000, 200, "返回", "", "", "", true, false),
        )
        val env = FakeEnv({ start }, refresh = { shifted })
        val (o, _, _) = harness(
            toolReply("连按两次 7", "tap" to """{"id":7}""", "tap" to """{"id":7}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
            env = env,
        )
        assertTrue(o.finished)
        // 第 1 下按步初树（中心 y=375），第 2 下按刷新后的位置（[800,900] 中心 850 → 425）
        assertEquals(listOf<Action>(Action.Tap(500, 375), Action.Tap(500, 425)), env.performed)
        assertEquals(1, env.refreshCount)
    }

    @Test
    fun batchedIdTapWhoseElementVanishedFailsAndStopsTheBatch() {
        val start = listOf(node(7, "关于本机"))
        val env = FakeEnv({ start }, refresh = { listOf(node(3, "别的页面")) })
        val (o, _, sent) = harness(
            toolReply("连按", "tap" to """{"id":7}""", "tap" to """{"id":7}""", "tap" to """{"id":7}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
            env = env,
        )
        assertTrue(o.finished)
        assertEquals(1, env.performed.size)
        val ex = o.transcript.steps[0].executions
        assertFalse(ex[1].ok); assertTrue(ex[1].result, ex[1].result.contains("不在") || ex[1].result.contains("消失"))
        assertTrue(ex[2].result.contains("未执行"))
        assertTrue(sent[1].contains(ex[1].result))
    }

    @Test
    fun withoutRefreshSupportBatchedTapsUseTheStepStartTree() {
        val env = FakeEnv({ listOf(node(7, "x"), node(0, "y")) }) // refresh 返回 null
        val (o, _, _) = harness(
            toolReply("连按", "tap" to """{"id":7}""", "tap" to """{"id":0}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
            env = env,
        )
        assertTrue(o.finished)
        assertEquals(listOf<Action>(Action.Tap(500, 375), Action.Tap(500, 25)), env.performed)
    }

    @Test
    fun callsBeyondThePerStepCapAreNotExecuted() {
        val many = Array(AgentLoop.MAX_CALLS_PER_STEP + 2) { "tap" to """{"id":7}""" }
        val (o, env, _) = harness(
            toolReply("狂按", *many),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
        )
        assertTrue(o.finished)
        assertEquals(AgentLoop.MAX_CALLS_PER_STEP, env.performed.size)
        assertEquals(AgentLoop.MAX_CALLS_PER_STEP + 2, o.transcript.steps[0].executions.size)
    }

    @Test
    fun observeProgressLineReportsOcrEntries() {
        val env = FakeEnv({ listOf(node(7, "x")) }).apply { ocrCount = 2 }
        val lines = mutableListOf<String>()
        val queue = mutableListOf(toolReply("1", "finish" to """{"summary":"s"}"""))
        val client = ArkChatClient("k", "u", transport = { ArkChatClient.HttpResult(200, queue.removeAt(0)) })
        runBlocking { AgentLoop(client, env, "glm", FakeSink()).run("t", 5) { lines += it } }
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("Observe") && it.contains("ocr+2") })
    }

    @Test
    fun environmentErrorTextIsFedBackToModelInsteadOfGenericFailure() {
        val env = FakeEnv({ listOf(node(7, "x")) }, performOk = false).apply { lastError = "屏幕上没有可输入的文本框" }
        val (o, _, sent) = harness(
            toolReply("输入", "type" to """{"text":"豆包"}"""),
            toolReply("改点搜索", "tap" to """{"id":7}"""),
            toolReply("完成", "finish" to """{"summary":"s"}"""),
            env = env,
        )
        assertFalse(o.transcript.steps[0].executions[0].ok)
        assertTrue(o.transcript.steps[0].executions[0].result.contains("没有可输入"))
        assertTrue(sent[1].contains("没有可输入"))
    }

    @Test
    fun sinkSeesStartAndEveryStep() {
        val sink = FakeSink()
        val env = FakeEnv({ listOf(node(7, "x")) })
        val queue = mutableListOf(toolReply("1", "tap" to """{"id":7}"""), toolReply("2", "finish" to """{"summary":"s"}"""))
        val client = ArkChatClient("k", "u", transport = { ArkChatClient.HttpResult(200, queue.removeAt(0)) })
        runBlocking { AgentLoop(client, env, "glm", sink).run("t", 5) { } }
        assertEquals("t", sink.started!!.task)
        assertEquals(listOf(1, 2), sink.steps)
    }

    @Test
    fun systemToolResultTextBecomesToolMessage() {
        val env = FakeEnv({ listOf(node(0, "返回")) }, system = { c ->
            if (c is com.androiduse.capability.SystemCall.CalendarQuery) SystemResult(true, "id=12 09-22 15:00–16:00 周会") else SystemResult(false, "不支持")
        })
        val (o, _, sent) = harness(
            toolReply("先查日历", "calendar_query" to "{}"),
            toolReply("找到了", "finish" to """{"summary":"周会在 9-22"}"""),
            env = env,
        )
        assertTrue(o.finished)
        assertEquals(1, env.systemCalls.size)
        assertTrue(env.performed.isEmpty())   // 没走 perform
        val ex = o.transcript.steps[0].executions[0]
        assertTrue(ex.ok)
        assertEquals("id=12 09-22 15:00–16:00 周会", ex.result)
        assertTrue(sent[1].contains("\"role\":\"tool\"") && sent[1].contains("周会"))
    }

    @Test
    fun unexpectedExceptionDuringExecuteBecomesFailedExecutionInsteadOfCrashingTheLoop() {
        // M5：Environment 实现里的意外异常（这里用 performSystem 模拟）不能让整个任务中止——
        // 兜成一次失败的 Execution，文本回给模型，循环继续走到下一步。
        val env = FakeEnv({ listOf(node(0, "返回")) }, system = { throw IllegalStateException("boom") })
        val (o, _, sent) = harness(
            toolReply("先查日历", "calendar_query" to "{}"),
            toolReply("换一种方式", "finish" to """{"summary":"done"}"""),
            env = env,
        )
        assertTrue(o.finished)
        val ex = o.transcript.steps[0].executions[0]
        assertFalse(ex.ok)
        assertTrue(ex.result, ex.result.contains("内部错误") && ex.result.contains("IllegalStateException") && ex.result.contains("boom"))
        assertTrue(sent[1].contains("内部错误"))
    }

    @Test
    fun systemToolFailureStopsBatchAndFeedsReasonBack() {
        val env = FakeEnv({ listOf(node(0, "返回")) }, system = { SystemResult(false, "缺 READ_CALENDAR 权限") })
        val (o, _, sent) = harness(
            toolReply("查并点", "calendar_query" to "{}", "tap" to """{"id":0}"""),
            toolReply("算了", "finish" to """{"summary":"x"}"""),
            env = env,
        )
        assertTrue(o.finished)
        val s = o.transcript.steps[0]
        assertFalse(s.executions[0].ok)
        assertEquals("缺 READ_CALENDAR 权限", s.executions[0].result)
        assertEquals(AgentLoop.NOT_EXECUTED_AFTER_FAILURE, s.executions[1].result)
        assertTrue(env.performed.isEmpty())
        assertTrue(sent[1].contains("READ_CALENDAR"))
    }

    private class FakeGrounder(private val answer: (String, List<NodeRecord>) -> LocateResult) : Grounder {
        val calls = mutableListOf<Pair<String, List<NodeRecord>>>()
        override fun locate(target: String, nodes: List<NodeRecord>, screenW: Int, screenH: Int): LocateResult {
            calls += target to nodes
            return answer(target, nodes)
        }
    }

    @Test
    fun targetTapIsLocatedThenInjectedAtTheNodeCenterAndReported() {
        val g = FakeGrounder { _, nodes -> LocateResult.Located(nodes.first { it.id == 7 }, 0.97, 640) }
        val (o, env, sent) = harness(
            toolReply("列表里没有，按描述点", "tap" to """{"target":"关于本机那一行"}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
            grounder = g,
        )
        assertTrue(o.finished)
        assertEquals(listOf<Action>(Action.Tap(500, 375)), env.performed)
        assertEquals("关于本机那一行", g.calls.single().first)
        val ex = o.transcript.steps[0].executions.single()
        assertTrue(ex.ok)
        assertEquals("按描述定位到 #7 text=\"关于本机\"（置信 0.97，Jev 640ms），已点击", ex.result)
        assertTrue(sent[0].contains("\"target\""))
    }

    @Test
    fun rejectedTargetTapFailsFeedsTheReasonBackAndStopsTheBatch() {
        val g = FakeGrounder { _, _ -> LocateResult.Rejected("没找到「搜索框」") }
        val (o, env, sent) = harness(
            toolReply("点搜索框再点关于", "tap" to """{"target":"搜索框"}""", "tap" to """{"id":7}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
            grounder = g,
        )
        assertTrue(o.finished)
        assertEquals(emptyList<Action>(), env.performed)
        val ex = o.transcript.steps[0].executions
        assertFalse(ex[0].ok)
        assertEquals("没找到「搜索框」", ex[0].result)
        assertTrue(ex[1].result.contains("未执行"))
        assertTrue(sent[1].contains("没找到「搜索框」"))
    }

    @Test
    fun batchedTargetTapIsLocatedInAFreshDump() {
        val start = listOf(node(7, "关于本机"), node(0, "返回"))
        val shifted = listOf(NodeRecord(8, 0, 800, 1000, 900, "去结算", "", "", "", true, false))
        val env = FakeEnv({ start }, refresh = { shifted })
        val g = FakeGrounder { _, nodes -> LocateResult.Located(nodes.first(), 0.9, 10) }
        val (o, _, _) = harness(
            toolReply("先点关于再结算", "tap" to """{"id":7}""", "tap" to """{"target":"去结算"}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
            env = env, grounder = g,
        )
        assertTrue(o.finished)
        assertEquals(shifted, g.calls.single().second)
        assertEquals(listOf<Action>(Action.Tap(500, 375), Action.Tap(500, 425)), env.performed)
        assertEquals(1, env.refreshCount)
    }

    @Test
    fun batchedTargetTapWithoutRefreshSupportUsesTheStepStartTree() {
        // Review Focus 4
        val start = listOf(node(7, "关于本机"), node(0, "返回"))
        val g = FakeGrounder { _, nodes -> LocateResult.Located(nodes.first { it.id == 0 }, 0.9, 10) }
        val (o, env, _) = harness(
            toolReply("连点", "tap" to """{"id":7}""", "tap" to """{"target":"返回"}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
            env = FakeEnv({ start }), grounder = g,
        )
        assertTrue(o.finished)
        assertEquals(start, g.calls.single().second)
        assertEquals(listOf<Action>(Action.Tap(500, 375), Action.Tap(500, 25)), env.performed)
    }

    @Test
    fun withoutGrounderTargetIsAnErrorAndToolsDoNotOfferIt() {
        val (o, env, sent) = harness(
            toolReply("按描述点", "tap" to """{"target":"关于本机"}"""),
            toolReply("完成", "finish" to """{"summary":"done"}"""),
        )
        assertTrue(o.finished)
        assertEquals(emptyList<Action>(), env.performed)
        assertTrue(o.transcript.steps[0].executions.single().result.contains("未启用"))
        assertFalse(sent[0].contains("\"target\""))
    }
}
