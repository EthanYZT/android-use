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

    fun resolve(
        call: ToolCall,
        nodes: List<NodeRecord>,
        screenW: Int,
        screenH: Int,
        apps: List<AppEntry> = emptyList(),
        /** 非 null 时（一步多动作的批内后续动作）：tap-by-id 在这棵新树里按身份重新定位。 */
        freshNodes: List<NodeRecord>? = null,
        /** 2a：calendar_query 默认时间范围用；测试注入固定时钟。 */
        nowMs: Long = System.currentTimeMillis(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Resolution {
        val a = call.argumentsJson
        return when (call.name) {
            "tap" -> {
                val id = ResponseParser.intField(a, "id")
                if (id != null) {
                    if (nodes.none { it.id == id }) return Resolution.Err("id $id 不在当前元素列表里，请重新查看列表或改用 x/y 坐标")
                    if (freshNodes != null) {
                        val tap = NodeGrounding.relocate(id, nodes, freshNodes, screenW, screenH)
                            ?: return Resolution.Err("id $id 的元素在执行前一个动作后已不在屏幕上（界面变了），本批后续动作未执行，请重新查看列表")
                        return Resolution.Ok(tap)
                    }
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
            "open_app" -> {
                val name = ResponseParser.field(a, "name")?.trim().orEmpty()
                if (name.isEmpty()) return Resolution.Err("open_app 需要 name（App 的显示名）")
                if (apps.isEmpty()) return Resolution.Err("当前没有可打开的 App 列表，open_app 不可用")
                val app = AppCatalog.resolve(name, apps)
                    ?: return Resolution.Err("没有名为 ${UntrustedText.sanitize(name)} 的 App。可用：${AppCatalog.promptList(apps)}")
                Resolution.Ok(Action.OpenApp(app.label, app.component))
            }
            "type" -> {
                val text = ResponseParser.field(a, "text") ?: return Resolution.Err("type 需要 text")
                val id = ResponseParser.intField(a, "id")
                if (id != null && nodes.none { it.id == id }) return Resolution.Err("id $id 不在当前元素列表里，请重新查看列表；不传 id 会写入当前焦点的输入框")
                val submit = a.contains("\"submit\":true") || a.contains("\"submit\": true")
                Resolution.Ok(Action.Type(text, id, submit))
            }
            "wait" -> Resolution.Ok(Action.Wait(Action.Wait.clamp(ResponseParser.intField(a, "ms") ?: 500)))
            "finish" -> Resolution.Ok(Action.Finish(ResponseParser.field(a, "summary") ?: ""))
            "handoff" -> Resolution.Ok(Action.Handoff(ResponseParser.field(a, "reason") ?: ""))
            in com.androiduse.capability.SystemCallParser.TOOL_NAMES -> {
                // TOOL_NAMES 成员理应总能让 parse 认出名字返回非 null（哪怕参数校验失败也是个
                // Err），但不能靠 !! 赌这个不变式——名字一旦不一致，要把它变成能反馈给模型的
                // Err，而不是让 NPE 冒到 AgentLoop 把整个任务中止掉。
                val r = com.androiduse.capability.SystemCallParser.parse(call.name, a, nowMs, zone)
                    ?: return Resolution.Err("没有名为 ${call.name.take(30)} 的工具")
                when (r) {
                    is com.androiduse.capability.SystemCallParser.Result.Ok -> Resolution.Ok(Action.System(r.call))
                    is com.androiduse.capability.SystemCallParser.Result.Err -> Resolution.Err(r.message)
                }
            }
            else -> Resolution.Err("没有名为 ${call.name.take(30)} 的工具")
        }
    }
}
