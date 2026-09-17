package com.androiduse.log

/**
 * 结构化任务日志。长任务 Agent 的 bug 极难复现（依赖 App 状态、网络、时序），
 * 日志就是调试基础设施（spec §11.2③）。每步记录：动作、通道、耗时、备注。
 */
class TaskLogger {
    private val _lines = mutableListOf<String>()
    val lines: List<String> get() = _lines

    fun step(index: Int, action: String, channel: String, costMs: Long, note: String): String {
        val line = "#$index [$channel] $action ${costMs}ms $note"
        _lines.add(line)
        return line
    }

    fun dump(): String = _lines.joinToString("\n")
}
