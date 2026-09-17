package com.androiduse.agent

import com.androiduse.actuation.Action

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
}

/** Transcript 的落盘/旁路观察者。默认实现全是空操作，方便测试和 CLI 按需实现。 */
interface TranscriptSink {
    fun start(t: Transcript) {}

    /** 保存一张截图，返回可写进日志的路径；不落盘返回 null。 */
    fun saveScreenshot(t: Transcript, stepIndex: Int, jpegBase64: String): String? = null

    /** 一步结束（execution 已填）时调用。 */
    fun step(t: Transcript, step: Step) {}
}
