package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord

/**
 * 解析模型输出。纯字符串处理，不用 org.json（JVM 单测里是桩实现会抛异常）。
 *
 * 容忍模型把 JSON 包在 markdown 代码块里或前面加解释文字——实测这很常见。
 *
 * 安全说明：模型输出（包括它转述的屏幕文字）是不可信输入。所有字段查找都必须
 * 锚定在真正的 key 位置上（前面是 { 或 ,，后面是 :），否则一个恰好包含
 * `"action"` / `"x"` / `"y"` 之类片段的字符串值就可能被误当成字段名命中，
 * 让解析静默返回错误的值，甚至让整条动作解析跑偏。
 */
object ResponseParser {

    /**
     * 从模型返回的正文里提取动作。解析不出来返回 null，由调用方决定重试还是中止。
     *
     * 1c：tap 支持两种形态——
     *  - `{"action":"tap","id":N}`：点节点列表里的元素 N，坐标从该节点 bounds 中心换算（走
     *    [nodes] + 屏幕尺寸解析）。这是首选，命中率高、零视觉误差。
     *  - `{"action":"tap","x":..,"y":..}`：归一化坐标，用于列表里没有的元素（图标等）的视觉兜底。
     * [nodes] 为空（守护进程读不到节点）时只有坐标形态可用，等同阶段 0 行为。
     */
    fun parseAction(
        modelContent: String,
        nodes: List<NodeRecord> = emptyList(),
        screenW: Int = 0,
        screenH: Int = 0,
    ): Action? {
        val json = extractFirstJsonObject(modelContent, 0) ?: return null
        return when (field(json, "action")?.lowercase()) {
            "tap" -> {
                val id = intField(json, "id")
                if (id != null) {
                    // id 不存在或 bounds 退化 → null（解析失败，让上层重试/中止）。
                    NodeGrounding.resolveTapId(id, nodes, screenW, screenH)
                } else {
                    val x = intField(json, "x") ?: return null
                    val y = intField(json, "y") ?: return null
                    Action.Tap(x, y)
                }
            }
            "swipe" -> {
                val x1 = intField(json, "x1") ?: return null
                val y1 = intField(json, "y1") ?: return null
                val x2 = intField(json, "x2") ?: return null
                val y2 = intField(json, "y2") ?: return null
                Action.Swipe(x1, y1, x2, y2, intField(json, "duration") ?: 300)
            }
            "back" -> Action.Back
            "home" -> Action.Home
            "wait" -> Action.Wait(Action.Wait.clamp(intField(json, "ms") ?: 500))
            "finish" -> Action.Finish(field(json, "summary") ?: "")
            else -> null
        }
    }

    /**
     * 一次模型回复里我们关心的三样东西：正文、结束原因、推理 token 数。
     *
     * 2026-09-17 真机复现：推理模型偶发把整个 max_tokens 烧在 reasoning_content 上，此时
     * finish_reason=length、content 为空串。只把 content 交给上层，"被截断"和"解析不出"
     * 长得一模一样，排障会被带偏（记忆里的"坑 2"），所以把后两项一起取出来。
     * finishReason / reasoningTokens 缺失时为 null（旧响应形态、或接口不返回 usage）。
     */
    data class Completion(
        val content: String,
        val finishReason: String?,
        val reasoningTokens: Int?,
        val toolCalls: List<ToolCall> = emptyList(),
    ) {
        /** 回复被 max_tokens 砍断（正文很可能不完整甚至为空）。 */
        val truncated: Boolean get() = finishReason == "length"
    }

    /**
     * 从方舟接口响应里取出 choices[0].message.content，连同 choices[0].finish_reason 和
     * usage.completion_tokens_details.reasoning_tokens。没有 content 返回 null。
     *
     * 不是找整份响应里第一次出现的 "content" 子串：这是个推理模型，同一个
     * message 对象里还会有 reasoning_content / encrypted_content 等字段，
     * 字段顺序不受我们控制。因此按结构逐层定位——choices 数组的第一个元素，
     * 取它的 message 对象，再取 message 自己的 content 字段——而不是依赖
     * 字段在文本里出现的先后顺序。
     */
    fun extractCompletion(apiResponseJson: String): Completion? {
        val choicesValueStart = valueStartOf(apiResponseJson, "choices", 0) ?: return null
        var i = choicesValueStart
        while (i < apiResponseJson.length && apiResponseJson[i] != '{') {
            if (apiResponseJson[i] == ']') return null // choices: [] 空数组
            i++
        }
        if (i >= apiResponseJson.length) return null
        val firstChoice = extractFirstJsonObject(apiResponseJson, i) ?: return null

        val messageValueStart = valueStartOf(firstChoice, "message", 0) ?: return null
        if (messageValueStart >= firstChoice.length || firstChoice[messageValueStart] != '{') return null
        val message = extractFirstJsonObject(firstChoice, messageValueStart) ?: return null

        val contentValueStart = valueStartOf(message, "content", 0) ?: return null
        if (contentValueStart >= message.length) return null
        val toolCalls = extractToolCalls(message)
        val content = when {
            message[contentValueStart] == '"' -> readJsonStringAt(message, contentValueStart)
            // 只发 tool_calls 时 content 常是 null；有 tool_calls 就不算"没有 content"。
            message.startsWith("null", contentValueStart) && toolCalls.isNotEmpty() -> ""
            else -> return null
        }

        // finish_reason 是 choice 的字段（与 message 平级）；reasoning_tokens 在顶层 usage 里，
        // 同样逐层定位，不在整份文本里裸搜。
        val finishReason = field(firstChoice, "finish_reason")
        val reasoningTokens = valueStartOf(apiResponseJson, "usage", 0)
            ?.let { extractFirstJsonObject(apiResponseJson, it) }
            ?.let { usage -> valueStartOf(usage, "completion_tokens_details", 0)?.let { extractFirstJsonObject(usage, it) } }
            ?.let { intField(it, "reasoning_tokens") }
        return Completion(content, finishReason, reasoningTokens, toolCalls)
    }

    /** message.tool_calls[*] → [ToolCall]。没有该字段返回空列表。 */
    private fun extractToolCalls(message: String): List<ToolCall> {
        val arrStart = valueStartOf(message, "tool_calls", 0) ?: return emptyList()
        if (arrStart >= message.length || message[arrStart] != '[') return emptyList()
        val out = ArrayList<ToolCall>()
        var i = arrStart + 1
        while (i < message.length) {
            val c = message[i]
            if (c == ']') break
            if (c == '{') {
                val obj = extractFirstJsonObject(message, i) ?: break
                val fnStart = valueStartOf(obj, "function", 0)
                val fn = fnStart?.let { if (it < obj.length && obj[it] == '{') extractFirstJsonObject(obj, it) else null }
                val name = fn?.let { field(it, "name") }
                val args = fn?.let { field(it, "arguments") }
                if (name != null) out.add(ToolCall(field(obj, "id") ?: "", name, args ?: "{}"))
                i += obj.length
            } else i++
        }
        return out
    }

    /** 只要正文。等价于 [extractCompletion] 的 content。 */
    fun extractContent(apiResponseJson: String): String? = extractCompletion(apiResponseJson)?.content

    /** 抓出从 fromIndex 起第一个 {...} 块，容忍前面有解释文字或 markdown fence。 */
    private fun extractFirstJsonObject(text: String, fromIndex: Int): String? {
        val start = text.indexOf('{', fromIndex)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * 读字段的值，处理转义。命中的必须是锚定的真 key——字符串值里恰好出现的
     * 同名片段（比如屏幕文字里的 "action":"tap"）不会被当成字段。
     */
    internal fun field(json: String, name: String): String? {
        val valueStart = valueStartOf(json, name, 0) ?: return null
        if (valueStart >= json.length) return null
        return if (json[valueStart] == '"') readJsonStringAt(json, valueStart)
        else json.substring(valueStart).takeWhile { it != ',' && it != '}' }.trim()
    }

    internal fun intField(json: String, name: String): Int? = field(json, name)?.trim()?.toIntOrNull()

    /**
     * 找到锚定的 key（紧跟在 { 或 , 之后，忽略中间空白；紧跟着 : ，也忽略空白），
     * 返回它的值开始的下标（已跳过 : 后面的空白）。找不到锚定的 key 返回 null。
     */
    private fun valueStartOf(json: String, name: String, fromIndex: Int): Int? {
        val key = "\"$name\""
        var searchFrom = fromIndex
        while (true) {
            val found = json.indexOf(key, searchFrom)
            if (found < 0) return null
            if (isAnchoredKey(json, found, key.length)) {
                var i = found + key.length
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] == ':') {
                    i++
                    while (i < json.length && json[i].isWhitespace()) i++
                    return i
                }
            }
            searchFrom = found + 1
        }
    }

    /** key 是否真的是字段名：前一个非空白字符是 { 或 ,，后一个非空白字符是 :。 */
    private fun isAnchoredKey(json: String, start: Int, keyLength: Int): Boolean {
        var before = start - 1
        while (before >= 0 && json[before].isWhitespace()) before--
        if (before < 0 || (json[before] != '{' && json[before] != ',')) return false

        var after = start + keyLength
        while (after < json.length && json[after].isWhitespace()) after++
        return after < json.length && json[after] == ':'
    }

    /** 从 openQuoteIndex 处的引号开始读一个 JSON 字符串，返回反转义后的内容。 */
    private fun readJsonStringAt(s: String, openQuoteIndex: Int): String {
        val sb = StringBuilder()
        var i = openQuoteIndex + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 5 < s.length) {
                            s.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            i += 4
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
