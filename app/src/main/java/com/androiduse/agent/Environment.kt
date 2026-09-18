package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.capability.SystemCall

/**
 * AgentLoop 与设备之间的边界：观察（截图 + 节点树）和执行（注入）。
 * Android 实现见 [com.androiduse.AndroidEnvironment]；单测用假实现覆盖循环的各条错误路径。
 */
interface Environment {
    val screenW: Int
    val screenH: Int

    /** 采集第 [stepIndex] 步开始时的屏幕。截图失败 → screenshotBase64 为 null；节点读不到 → dumpError 非空。 */
    fun observe(stepIndex: Int): Observation

    /** 执行动作，返回注入是否成功。 */
    fun perform(action: Action): Boolean

    /** 最近一次 perform 失败的原因文本（如守护进程对 type 的拒绝理由），回给模型；没有则 null。 */
    fun lastError(): String? = null

    /**
     * 桌面可启动的 App（显示名 + 启动组件），供 open_app 按名字解析。任务开始时取一次，
     * 记进 Transcript 后整个任务不变。查不到（无 Context 等）返回空列表，open_app 即不可用。
     */
    fun installedApps(): List<AppEntry>

    /**
     * 只重新读一次节点树（不截图、不调模型），供一步多动作时批内后续动作重新定位：
     * 前一个动作可能让界面重排（计算器切到科学布局），步初的 id/坐标就不再可靠。
     * 不支持刷新的环境返回 null，调用方退回用步初的树。
     */
    fun refreshNodes(): List<com.androiduse.daemon.DumpCodec.NodeRecord>? = null

    /**
     * 2a：执行一个系统接口调用（Intent 落虚拟屏 / ContentProvider 读写）。返回的 text 直接作为 tool 消息
     * 回给模型（查询结果、"已创建事件 id=N"、失败原因）。默认实现：不支持。
     */
    fun performSystem(call: SystemCall): SystemResult = SystemResult(false, "此环境不支持系统接口工具")
}

/** Transcript 的落盘/旁路观察者。默认实现全是空操作，方便测试和 CLI 按需实现。 */
interface TranscriptSink {
    fun start(t: Transcript) {}

    /** 保存一张截图，返回可写进日志的路径；不落盘返回 null。 */
    fun saveScreenshot(t: Transcript, stepIndex: Int, jpegBase64: String): String? = null

    /** 一步结束（execution 已填）时调用。 */
    fun step(t: Transcript, step: Step) {}

    /** 任务结束（完成或中止）时调用一次。 */
    fun outcome(t: Transcript, finished: Boolean, summary: String) {}
}

/** 系统接口调用的结果。ok=false 时 text 是给模型看的失败原因。 */
data class SystemResult(val ok: Boolean, val text: String)
