package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord

/**
 * 把模型的一个 tool call 解析成 [Action]。
 *
 * 解析失败返回 [Resolution.Err] 而不是 null：错误文本会被 AgentLoop 写进 tool 结果反馈给模型
 * （"id 99 不在当前列表里"），让它下一轮自己纠正——这比直接中止任务便宜得多。
 *
 * arguments 是模型给的不可信 JSON 字符串，字段查找走 [ResponseParser] 的锚定式解析，
 * 不裸搜子串。
 */
object ToolCallResolver {

    sealed class Resolution {
        data class Ok(val action: Action) : Resolution()
        data class Err(val message: String) : Resolution()
    }

    fun resolve(call: ToolCall, nodes: List<NodeRecord>, screenW: Int, screenH: Int): Resolution {
        val a = call.argumentsJson
        return when (call.name) {
            "tap" -> {
                val id = ResponseParser.intField(a, "id")
                if (id != null) {
                    if (nodes.none { it.id == id }) return Resolution.Err("id $id 不在当前元素列表里，请重新查看列表或改用 x/y 坐标")
                    val tap = NodeGrounding.resolveTapId(id, nodes, screenW, screenH)
                        ?: return Resolution.Err("id $id 的元素没有有效位置，请改用 x/y 坐标")
                    Resolution.Ok(tap)
                } else {
                    val x = ResponseParser.intField(a, "x")
                    val y = ResponseParser.intField(a, "y")
                    if (x == null || y == null) Resolution.Err("tap 需要 id 或 x/y 坐标")
                    else Resolution.Ok(Action.Tap(x, y))
                }
            }
            "swipe" -> {
                val x1 = ResponseParser.intField(a, "x1"); val y1 = ResponseParser.intField(a, "y1")
                val x2 = ResponseParser.intField(a, "x2"); val y2 = ResponseParser.intField(a, "y2")
                if (x1 == null || y1 == null || x2 == null || y2 == null) Resolution.Err("swipe 需要 x1/y1/x2/y2")
                else Resolution.Ok(Action.Swipe(x1, y1, x2, y2, ResponseParser.intField(a, "duration") ?: 300))
            }
            "back" -> Resolution.Ok(Action.Back)
            "wait" -> Resolution.Ok(Action.Wait(Action.Wait.clamp(ResponseParser.intField(a, "ms") ?: 500)))
            "finish" -> Resolution.Ok(Action.Finish(ResponseParser.field(a, "summary") ?: ""))
            else -> Resolution.Err("没有名为 ${call.name.take(30)} 的工具")
        }
    }
}
