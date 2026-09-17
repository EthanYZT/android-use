package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.actuation.Injector
import com.androiduse.display.VirtualScreen
import com.androiduse.log.TaskLogger
import com.androiduse.perception.ScreenCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阶段 0 的最小闭环：截图 → 模型决策 → 注入 → 再截图。
 *
 * 阶段 0 用单个视觉模型同时做决策和定位；spec §10.1 的 Planner/Grounder 分层
 * 留到阶段 1 再拆——接口已按可替换设计，拆分不影响本循环。
 */
class AgentLoop(
    private val client: ArkVisionClient,
    private val logger: TaskLogger,
) {

    suspend fun run(
        task: String,
        screen: VirtualScreen,
        maxSteps: Int = 15,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val history = mutableListOf<String>()

        for (step in 1..maxSteps) {
            val t0 = System.currentTimeMillis()

            val frame = ScreenCapture.captureAsJpegBase64(screen)
                ?: return@withContext finish(step, "截图失败", onProgress)

            val (action, raw) = client.decideNextAction(task, history, frame)
            if (action == null) {
                return@withContext finish(step, "模型响应无法解析: ${raw.take(200)}", onProgress)
            }

            if (action is Action.Finish) {
                val line = logger.step(step, "Finish", "gui", System.currentTimeMillis() - t0, action.summary)
                onProgress(line)
                return@withContext "任务完成: ${action.summary}"
            }

            val ok = Injector.perform(action, screen)
            val cost = System.currentTimeMillis() - t0
            val line = logger.step(step, action.toString(), "gui", cost, if (ok) "ok" else "注入失败")
            onProgress(line)

            if (!ok) return@withContext "第 $step 步注入失败，已中止"
            history.add(action.toString())
        }

        "达到最大步数 $maxSteps，未收到 finish，已中止"
    }

    private fun finish(step: Int, reason: String, onProgress: (String) -> Unit): String {
        val line = logger.step(step, "Abort", "gui", 0, reason)
        onProgress(line)
        return reason
    }
}
