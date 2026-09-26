package com.androiduse.actuation

/**
 * Agent 可执行的动作。坐标一律是归一化到 [0,1000] 的整数，与截图缩放解耦。
 * 阶段 0 只支持最小集合；输入文本、长按等留到阶段 2 以后。
 */
sealed class Action {
    data class Tap(val xNorm: Int, val yNorm: Int) : Action()

    /**
     * 按描述点击（spec 2026-09-26）：[target] 是规划器对元素的自然语言描述。不直接注入——
     * AgentLoop 先经 Grounder 在全量节点里定位成普通 [Tap] 再执行；toShell 对它返回 null。
     */
    data class TapTarget(val target: String) : Action()
    data class Swipe(
        val x1Norm: Int, val y1Norm: Int,
        val x2Norm: Int, val y2Norm: Int,
        val durationMs: Int = 300,
    ) : Action()
    data object Back : Action()
    data object Home : Action()
    data class Wait(val ms: Int) : Action() {
        companion object {
            // F-1: 模型输出的 ms 是不可信输入。负值会让 Thread.sleep 直接抛
            // IllegalArgumentException（无人 catch，一路崩到 MainActivity 的 lifecycleScope）；
            // 巨大值会在 withContext(Dispatchers.IO) 里长时间阻塞而不可取消（ensureActive()
            // 只在步骤边界检查，睡眠中途拦不住）。上限取 10 秒——GUI 等待场景够用，
            // 也不会让单步显著拖长循环。两处都要夹紧：解析边界（ResponseParser）防止
            // 存进 Action 的值本身就出格；Injector 再夹一次，防止未来出现不经
            // ResponseParser 构造 Action.Wait 的调用方重新捅穿这个洞。
            const val MIN_MS = 0
            const val MAX_MS = 10_000
            fun clamp(ms: Int): Int = ms.coerceIn(MIN_MS, MAX_MS)
        }
    }
    /**
     * 在虚拟屏上启动一个 App。[label] 是模型点名用的显示名（只用于日志），[component] 是
     * `包名/Activity`，由 [com.androiduse.agent.AppCatalog] 从 PackageManager 查出的白名单里解析，
     * 模型给的文字不会直接出现在这里。
     */
    data class OpenApp(val label: String, val component: String) : Action()

    /**
     * 往文本框输入文字。走守护进程的无障碍 ACTION_SET_TEXT（不可见虚拟屏上没有键盘，`input text` 不支持中文）。
     * [nodeId] 为空则写入当前焦点/首个可编辑节点；[submit] 为 true 时输入后发 IME 回车触发搜索/确认。
     */
    data class Type(val text: String, val nodeId: Int?, val submit: Boolean) : Action()

    /**
     * 2a：系统接口工具（闹钟/日历/联系人/短信/拨号/导航/设置页）。参数已由
     * [com.androiduse.capability.SystemCallParser] 校验；执行走 Environment.performSystem，
     * 不经 Injector / ActionCommand（toShell 对它返回 null）。
     */
    data class System(val call: com.androiduse.capability.SystemCall) : Action()

    /** 任务完成。summary 是模型对结果的自述，用于日志与验收。 */
    data class Finish(val summary: String) : Action()

    /**
     * 交接：模型做到需要本人操作的一步（付款/提交订单/结算确认、滑块/选图类人机验证）停下，
     * reason 说明停在哪、用户接着做什么。与 Finish 一样结束循环；不经 Injector（toShell 返回 null）。
     */
    data class Handoff(val reason: String) : Action()
}
