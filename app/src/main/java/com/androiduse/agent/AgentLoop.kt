package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.actuation.Injector
import com.androiduse.display.VirtualScreen
import com.androiduse.log.TaskLogger
import com.androiduse.perception.ScreenCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
            // 循环体里的调用（截图/HTTP/Thread.sleep 注入）全是阻塞调用，没有天然的
            // 挂起点，取消外层 Job 不会让它们中途中断。在每轮边界显式检查一次，能让
            // 一次 cancel()（比如 Activity 被销毁）最迟在当前这一步跑完后的下一轮
            // 边界就生效，而不是硬跑到 maxSteps——避免继续截图/继续调用付费的模型
            // API/继续通过 onProgress 闭包吊住已销毁的 Activity。
            ensureActive()

            val t0 = System.currentTimeMillis()

            val frame = ScreenCapture.captureAsJpegBase64(screen)
                ?: return@withContext finish(step, System.currentTimeMillis() - t0, "截图失败", onProgress)

            val (action, raw) = client.decideNextAction(task, history, frame)
            // F-8：原始响应是阶段 1 grounding 排障最有价值的语料——一次"解析成功但点错
            // 位置"的 tap，事后只能靠这份原始文本复盘。之前只在解析失败时记录，成功路径
            // 完全不可恢复。这里无论成败都记一条并推给 onProgress，进同一份可查的日志，
            // 截断长度与 ArkVisionClient 的截断一致（300 字符，两处不一致是已知 deferred 项）。
            onProgress(logger.step(step, "RawResponse", "gui", 0, raw.take(300)))

            if (action == null) {
                return@withContext finish(
                    step,
                    System.currentTimeMillis() - t0,
                    "模型响应无法解析: ${raw.take(200)}",
                    onProgress,
                )
            }

            if (action is Action.Finish) {
                val line = logger.step(step, "Finish", "gui", System.currentTimeMillis() - t0, action.summary)
                onProgress(line)
                return@withContext "任务完成: ${action.summary}"
            }

            // F-3：Action.Home 的拒绝不再放在这里判断——它已经下沉到 ActionCommand.toShell /
            // Injector.perform（构造即拒绝，见那两处的注释），任何调用 Injector.perform 的
            // 路径都无法绕过，不必也不应该在这一层重复判断。模型选中 Home 时会走下面的
            // Injector.perform，拿到 ok=false，按普通注入失败中止——效果等价，且不可被绕过。
            val ok = Injector.perform(action, screen)
            val cost = System.currentTimeMillis() - t0
            val line = logger.step(step, action.toString(), "gui", cost, if (ok) "ok" else "注入失败")
            onProgress(line)

            if (!ok) return@withContext "第 $step 步注入失败，已中止"
            history.add(action.toString())
        }

        "达到最大步数 $maxSteps，未收到 finish，已中止"
    }

    private fun finish(step: Int, costMs: Long, reason: String, onProgress: (String) -> Unit): String {
        val line = logger.step(step, "Abort", "gui", costMs, reason)
        onProgress(line)
        return reason
    }
}
