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

            if (action is Action.Home) {
                // 2026-09-17 实测：overlay 虚拟屏上的 HOME 键不会被 -d 参数隔离在目标屏
                // 内，会被系统路由到物理屏（display 0）的桌面 Launcher，直接抢走物理
                // 前台——正是验收标准③要保护的东西。目前没有真正按屏隔离的 Home 实现，
                // 所以宁可拒绝执行也不能悄悄捅穿物理屏；PromptBuilder 已经不再主动提供
                // 这个动作，这里是防模型仍然选中它的兜底。阶段 1 需要解决隔离问题本身，
                // 而不是重新发现这个坑。
                val cost = System.currentTimeMillis() - t0
                val line = logger.step(
                    step,
                    "Home",
                    "gui",
                    cost,
                    "拒绝执行: HOME 键会跨屏抢占物理屏前台(已知问题), 未注入",
                )
                onProgress(line)
                return@withContext "第 $step 步拒绝执行 Home 动作，已中止"
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

    private fun finish(step: Int, costMs: Long, reason: String, onProgress: (String) -> Unit): String {
        val line = logger.step(step, "Abort", "gui", costMs, reason)
        onProgress(line)
        return reason
    }
}
