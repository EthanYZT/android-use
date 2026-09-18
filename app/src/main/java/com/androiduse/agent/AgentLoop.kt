package com.androiduse.agent

import com.androiduse.actuation.Action
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 对话式 harness：整个任务是一份持续增长的 [Transcript]，每步从它生成 messages
 * （[PromptBuilder]）发给模型，模型的笔记和每步的执行结果都留在里面——记忆就是对话本身。
 * 与 Claude Code / Codex 的 agent loop 同构：assistant 发 tool call，我们执行后以 tool 消息回填。
 *
 * 错误处理原则：**能反馈给模型的都反馈给模型**（id 不存在、注入失败），让它自己纠正；
 * 只有它连续失败、原地打转、或根本不调用工具时才中止。
 */
class AgentLoop(
    private val client: ArkChatClient,
    private val env: Environment,
    private val model: String,
    private val sink: TranscriptSink = object : TranscriptSink {},
) {
    data class Outcome(val finished: Boolean, val summary: String, val transcript: Transcript)

    companion object {
        /** 连续这么多步执行失败（解析失败或注入失败）就中止，避免死磕。 */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** 连续这么多步"屏幕节点列表相同且动作相同"判定卡住。这是兜底，不是主要防线。 */
        const val STUCK_REPEATS = 3

        /** 一步内最多顺序执行多少个 tool call（键盘/键区连按够用，防模型一口气发几十个）。 */
        const val MAX_CALLS_PER_STEP = 8

        const val NOT_EXECUTED_AFTER_FAILURE = "未执行（前一个动作失败）"
        const val NOT_EXECUTED_AFTER_FINISH = "未执行（已 finish）"
        const val NOT_EXECUTED_OVER_CAP = "未执行（单步最多 $MAX_CALLS_PER_STEP 个动作）"
    }

    suspend fun run(
        task: String,
        maxSteps: Int = 15,
        onProgress: (String) -> Unit,
    ): Outcome = withContext(Dispatchers.IO) {
        val o = runSteps(task, maxSteps, onProgress)
        sink.outcome(o.transcript, o.finished, o.summary)
        o
    }

    private suspend fun runSteps(task: String, maxSteps: Int, onProgress: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        val t = Transcript(
            taskId = "task-${System.currentTimeMillis()}",
            task = task, model = model,
            startedAtMs = System.currentTimeMillis(),
            screenW = env.screenW, screenH = env.screenH,
            apps = env.installedApps(),
        )
        sink.start(t)

        var consecutiveFailures = 0
        var lastSignature: String? = null
        var repeats = 0

        for (i in 1..maxSteps) {
            // 循环体里全是阻塞调用（截图/HTTP/注入），没有天然挂起点；在每轮边界显式检查一次，
            // 让外层 cancel()（比如 Activity 销毁）最迟在当前步跑完后生效。
            ensureActive()
            val t0 = System.currentTimeMillis()

            val raw = env.observe(i)
            val path = raw.screenshotBase64?.let { sink.saveScreenshot(t, i, it) }
            val obs = raw.copy(screenshotPath = path)
            val step = Step(i, obs)
            t.steps += step
            onProgress(line(i, "Observe", 0, "nodes=${obs.nodes.size}" +
                (if (obs.ocrCount > 0) " ocr+${obs.ocrCount}" else "") + (obs.ocrError?.let { " ocr失败: ${it.take(60)}" } ?: "") +
                (obs.dumpError?.let { " 节点读取失败: ${it.take(80)}" } ?: "") + (if (obs.screenshotBase64 == null) " 截图失败" else "")))
            if (obs.screenshotBase64 == null && obs.nodes.isEmpty()) {
                return@withContext abort(t, step, t0, "截图失败且读不到节点，无法观察屏幕", onProgress)
            }

            // 调模型；只给文字不给 tool call 时追问一次。
            var reply: ModelReply? = null
            for (attempt in 0..1) {
                val body = PromptBuilder.buildRequestBody(t)
                val r = when (val res = client.chat(body)) {
                    is ArkChatClient.Result.Err -> return@withContext abort(t, step, t0, "模型请求失败: ${res.message}", onProgress)
                    is ArkChatClient.Result.Ok -> ModelReply(
                        note = res.completion.content,
                        toolCalls = res.completion.toolCalls,
                        finishReason = res.completion.finishReason,
                        reasoningTokens = res.completion.reasoningTokens,
                        latencyMs = res.latencyMs,
                    )
                }
                step.replies += r
                onProgress(line(i, "Reply", r.latencyMs, "笔记: ${r.note.take(120)} | 工具: ${r.toolCalls.joinToString { "${it.name}${it.argumentsJson}" }.take(120)} | 推理 ${r.reasoningTokens ?: "?"} tok"))
                if (r.toolCalls.isNotEmpty()) { reply = r; break }
                if (attempt == 1) return@withContext abort(t, step, t0, "模型两次都没有调用工具，只给了文字：${r.note.take(100)}", onProgress)
                onProgress(line(i, "Nudge", 0, PromptBuilder.NUDGE_TEXT))
            }
            val calls = reply!!.toolCalls

            // 一步多动作：按顺序执行，任一失败后其余标记未执行；finish 出现在中间则先执行它前面的再结束。
            var finish: Action.Finish? = null
            var stopped: String? = null
            var anyFailed = false
            var lastAction: Action? = null
            var executedCount = 0
            for ((ci, call) in calls.withIndex()) {
                val c0 = System.currentTimeMillis()
                if (stopped != null) { step.executions += Execution(null, false, stopped, 0); continue }
                if (ci >= MAX_CALLS_PER_STEP) { stopped = NOT_EXECUTED_OVER_CAP; step.executions += Execution(null, false, stopped, 0); continue }
                executedCount++
                // 批内第二个动作起，tap-by-id 先重新 dump 一次节点树按身份定位（前一个动作可能已让界面重排）。
                val fresh = if (ci > 0 && call.name == "tap" && ResponseParser.intField(call.argumentsJson, "id") != null) env.refreshNodes() else null
                val exec = when (val res = ToolCallResolver.resolve(call, obs.nodes, env.screenW, env.screenH, t.apps, fresh)) {
                    is ToolCallResolver.Resolution.Err -> Execution(null, false, res.message, System.currentTimeMillis() - c0)
                    is ToolCallResolver.Resolution.Ok -> {
                        val action = res.action
                        if (action is Action.Finish) {
                            finish = action
                            Execution(action, true, "finish", System.currentTimeMillis() - c0)
                        } else {
                            val ok = env.perform(action)
                            Execution(action, ok, if (ok) "ok" else "注入失败", System.currentTimeMillis() - c0)
                        }
                    }
                }
                step.executions += exec
                if (exec.ok) lastAction = exec.action
                if (!exec.ok) { anyFailed = true; stopped = NOT_EXECUTED_AFTER_FAILURE }
                else if (finish != null) stopped = NOT_EXECUTED_AFTER_FINISH
                onProgress(line(i, exec.action?.toString() ?: call.name, exec.costMs, exec.result))
            }
            val summary = Execution(
                action = lastAction,
                ok = !anyFailed,
                result = if (step.executions.size == 1) step.executions[0].result else step.executions.joinToString("; ") { it.result },
                costMs = System.currentTimeMillis() - t0,
            )
            step.execution = summary
            sink.step(t, step)
            if (finish != null && !anyFailed) {
                onProgress(line(i, "Finish", summary.costMs, finish!!.summary))
                return@withContext Outcome(true, finish!!.summary, t)
            }
            if (calls.size > 1) onProgress(line(i, "Step", summary.costMs, "$executedCount/${calls.size} 个动作已执行" + (if (anyFailed) "，中途失败" else "")))

            consecutiveFailures = if (!anyFailed) 0 else consecutiveFailures + 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                return@withContext Outcome(false, "连续 $MAX_CONSECUTIVE_FAILURES 步执行失败，已中止。最后一次: ${summary.result}", t)
            }

            val signature = obs.nodesBlock + " " + calls.joinToString("|") { it.name + it.argumentsJson }
            repeats = if (signature == lastSignature) repeats + 1 else 1
            lastSignature = signature
            if (repeats >= STUCK_REPEATS) {
                return@withContext Outcome(false, "连续 $STUCK_REPEATS 步屏幕与动作完全相同，判定卡住，已中止", t)
            }
        }

        val lastNote = t.steps.lastOrNull()?.replies?.lastOrNull()?.note.orEmpty()
        Outcome(false, "达到最大步数 $maxSteps，未收到 finish，已中止。最后笔记：$lastNote", t)
    }

    private fun abort(t: Transcript, step: Step, t0: Long, reason: String, onProgress: (String) -> Unit): Outcome {
        step.execution = Execution(null, false, reason, System.currentTimeMillis() - t0)
        sink.step(t, step)
        onProgress(line(step.index, "Abort", step.execution!!.costMs, reason))
        return Outcome(false, reason, t)
    }

    private fun line(index: Int, what: String, costMs: Long, note: String) = "#$index $what ${costMs}ms $note"
}
