package com.androiduse.agent

import com.androiduse.actuation.Action

/**
 * 解析模型输出。纯字符串处理，不用 org.json（JVM 单测里是桩实现会抛异常）。
 *
 * 容忍模型把 JSON 包在 markdown 代码块里或前面加解释文字——实测这很常见。
 */
object ResponseParser {

    /** 从模型返回的正文里提取动作。解析不出来返回 null，由调用方决定重试还是中止。 */
    fun parseAction(modelContent: String): Action? {
        val json = extractFirstJsonObject(modelContent) ?: return null
        return when (field(json, "action")?.lowercase()) {
            "tap" -> {
                val x = intField(json, "x") ?: return null
                val y = intField(json, "y") ?: return null
                Action.Tap(x, y)
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
            "wait" -> Action.Wait(intField(json, "ms") ?: 500)
            "finish" -> Action.Finish(field(json, "summary") ?: "")
            else -> null
        }
    }

    /** 从方舟接口响应里取出 choices[0].message.content。 */
    fun extractContent(apiResponseJson: String): String? {
        val marker = "\"content\""
        val idx = apiResponseJson.indexOf(marker)
        if (idx < 0) return null
        val colon = apiResponseJson.indexOf(':', idx + marker.length)
        if (colon < 0) return null
        val quote = apiResponseJson.indexOf('"', colon + 1)
        if (quote < 0) return null
        return readJsonStringAt(apiResponseJson, quote)
    }

    /** 抓出第一个 {...} 块，容忍前后有解释文字或 markdown fence。 */
    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
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

    /** 读字符串型字段的值，处理转义。 */
    private fun field(json: String, name: String): String? {
        val key = "\"$name\""
        val idx = json.indexOf(key)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        return if (json[i] == '"') readJsonStringAt(json, i)
        else json.substring(i).takeWhile { it != ',' && it != '}' }.trim()
    }

    private fun intField(json: String, name: String): Int? = field(json, name)?.trim()?.toIntOrNull()

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
