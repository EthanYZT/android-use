package com.androiduse.daemon

/**
 * 守护进程 ←→ App 之间的线协议编解码。**纯逻辑，无 Android 依赖，可单测。**
 *
 * 分帧：一行一帧，UTF-8，`\n` 结尾。请求是 App 发给守护进程的命令，响应是守护进程回给
 * App 的一行 JSON。
 *
 * 信任边界：这条通道两端都是我们自己的代码，是**可信**通道，所以这里携带节点原文（只做
 * JSON 字符串转义保证不破坏线格式），不做面向提示词的净化/截断——那是 1c 用 [com.androiduse
 * .agent.UntrustedText] 在构造提示词时才做的事。两者职责分开：wire 保真，prompt 层设防。
 */
object DumpCodec {

    /** 一个被抽取出来的节点。id 是本次 dump 内的稳定序号，供模型按编号引用（1c）。 */
    data class NodeRecord(
        val id: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val text: String,
        val desc: String,
        val resId: String,
        val className: String,
        val clickable: Boolean,
        val scrollable: Boolean,
    )

    /** dump 请求。displayId 是逻辑屏 id（虚拟屏）。 */
    data class DumpRequest(val displayId: Int)

    // ---- 请求解析（App → 守护进程）----

    /**
     * 解析一行请求。当前只有 dump 一种命令。解析不出返回 null（调用方按协议错误处理）。
     * 手写而非 org.json：与项目其余部分一致，且守护进程侧要尽量少依赖。
     */
    fun parseRequest(line: String): DumpRequest? {
        val s = line.trim()
        if (!s.contains("\"cmd\"")) return null
        val cmd = strField(s, "cmd") ?: return null
        if (cmd != "dump") return null
        val displayId = intField(s, "displayId") ?: return null
        return DumpRequest(displayId)
    }

    fun encodeRequest(req: DumpRequest): String =
        "{\"cmd\":\"dump\",\"displayId\":${req.displayId}}"

    // ---- 响应编码（守护进程 → App）----

    fun encodeOk(displayId: Int, nodes: List<NodeRecord>): String {
        val arr = nodes.joinToString(",") { encodeNode(it) }
        return "{\"ok\":true,\"displayId\":$displayId,\"nodes\":[$arr]}"
    }

    fun encodeError(message: String): String =
        "{\"ok\":false,\"error\":${jsonStr(message)}}"

    // ---- 响应解析（App 侧消费守护进程的回复）----

    /** dump 的结果：成功带节点，失败带错误信息。 */
    sealed class DumpResult {
        data class Ok(val displayId: Int, val nodes: List<NodeRecord>) : DumpResult()
        data class Err(val message: String) : DumpResult()
    }

    /**
     * 解析守护进程回来的一行响应。这是我们自有的受控格式，用同一套原语解析。
     * 解析不出（截断/损坏）当作 Err，让 App 侧走重试/中止。
     */
    fun parseResponse(line: String): DumpResult {
        val s = line.trim()
        if (boolField(s, "ok") != true) {
            return DumpResult.Err(strField(s, "error") ?: "unparseable response: ${s.take(80)}")
        }
        val displayId = intField(s, "displayId") ?: return DumpResult.Err("missing displayId")
        val nodes = parseNodesArray(s)
        return DumpResult.Ok(displayId, nodes)
    }

    /** 取 `"key":true|false`。找不到返回 null。 */
    internal fun boolField(json: String, key: String): Boolean? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val k = json.indexOf(needle, from)
            if (k < 0) return null
            var p = k - 1
            while (p >= 0 && json[p].isWhitespace()) p--
            if (p >= 0 && (json[p] == '{' || json[p] == ',')) {
                var i = k + needle.length
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] == ':') {
                    i++
                    while (i < json.length && json[i].isWhitespace()) i++
                    if (json.startsWith("true", i)) return true
                    if (json.startsWith("false", i)) return false
                }
            }
            from = k + needle.length
        }
    }

    /** 从响应里切出 `"nodes":[ {..},{..} ]` 的每个对象并解析。 */
    private fun parseNodesArray(json: String): List<NodeRecord> {
        val marker = "\"nodes\""
        val m = json.indexOf(marker)
        if (m < 0) return emptyList()
        var i = json.indexOf('[', m)
        if (i < 0) return emptyList()
        i++ // 进入数组
        val out = ArrayList<NodeRecord>()
        while (i < json.length) {
            while (i < json.length && json[i] != '{' && json[i] != ']') i++
            if (i >= json.length || json[i] == ']') break
            // 从 i 处切出一个平衡的 {...}（对象内不含嵌套对象，但仍按括号配平以防万一）。
            val objEnd = matchBrace(json, i)
            if (objEnd < 0) break
            val obj = json.substring(i, objEnd + 1)
            parseNode(obj)?.let { out.add(it) }
            i = objEnd + 1
        }
        return out
    }

    private fun matchBrace(json: String, start: Int): Int {
        var depth = 0
        var i = start
        var inStr = false
        while (i < json.length) {
            val c = json[i]
            if (inStr) {
                if (c == '\\') i++ else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return i }
                }
            }
            i++
        }
        return -1
    }

    private fun parseNode(obj: String): NodeRecord? {
        val id = intField(obj, "id") ?: return null
        val b = parseIntArray(obj, "b")
        if (b.size != 4) return null
        return NodeRecord(
            id = id,
            left = b[0], top = b[1], right = b[2], bottom = b[3],
            text = strField(obj, "text").orEmpty(),
            desc = strField(obj, "desc").orEmpty(),
            resId = strField(obj, "resId").orEmpty(),
            className = strField(obj, "cls").orEmpty(),
            clickable = boolField(obj, "click") ?: false,
            scrollable = boolField(obj, "scroll") ?: false,
        )
    }

    /** 取 `"key":[i0,i1,...]` 的整数数组。 */
    private fun parseIntArray(json: String, key: String): List<Int> {
        val needle = "\"$key\""
        val k = json.indexOf(needle)
        if (k < 0) return emptyList()
        var i = json.indexOf('[', k)
        if (i < 0) return emptyList()
        val end = json.indexOf(']', i)
        if (end < 0) return emptyList()
        return json.substring(i + 1, end)
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
    }

    private fun encodeNode(n: NodeRecord): String = buildString {
        append("{\"id\":").append(n.id)
        append(",\"b\":[").append(n.left).append(',').append(n.top).append(',')
            .append(n.right).append(',').append(n.bottom).append(']')
        append(",\"text\":").append(jsonStr(n.text))
        append(",\"desc\":").append(jsonStr(n.desc))
        append(",\"resId\":").append(jsonStr(n.resId))
        append(",\"cls\":").append(jsonStr(n.className))
        append(",\"click\":").append(n.clickable)
        append(",\"scroll\":").append(n.scrollable)
        append('}')
    }

    // ---- 极简 JSON 取值（只在受控的自有协议上用，非通用解析器）----

    /** 取 `"key":"value"` 的字符串值，处理常见转义。找不到返回 null。 */
    internal fun strField(json: String, key: String): String? {
        val at = keyValueStart(json, key) ?: return null
        if (at >= json.length || json[at] != '"') return null
        val sb = StringBuilder()
        var i = at + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    when (val e = json[i + 1]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'u' -> {
                            if (i + 5 < json.length) {
                                val code = json.substring(i + 2, i + 6).toIntOrNull(16)
                                if (code != null) { sb.append(code.toChar()); i += 4 }
                            }
                        }
                        else -> sb.append(e)
                    }
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> { sb.append(c); i++ }
            }
        }
        return null
    }

    /** 取 `"key":<int>` 的整数值（允许负号）。找不到/非整数返回 null。 */
    internal fun intField(json: String, key: String): Int? {
        val at = keyValueStart(json, key) ?: return null
        var i = at
        val start = i
        if (i < json.length && json[i] == '-') i++
        while (i < json.length && json[i].isDigit()) i++
        if (i == start || (i == start + 1 && json[start] == '-')) return null
        return json.substring(start, i).toIntOrNull()
    }

    /**
     * 定位 `"key"` 之后、跳过冒号和空白后的值起始下标。key 必须是真正的键（前面紧邻 `{`
     * 或 `,`，允许空白），避免命中某个字符串值里恰好含 `"key"` 片段。
     */
    private fun keyValueStart(json: String, key: String): Int? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val k = json.indexOf(needle, from)
            if (k < 0) return null
            // 校验 key 前一个非空白字符是 { 或 ,
            var p = k - 1
            while (p >= 0 && json[p].isWhitespace()) p--
            val prevOk = p >= 0 && (json[p] == '{' || json[p] == ',')
            if (prevOk) {
                var i = k + needle.length
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] == ':') {
                    i++
                    while (i < json.length && json[i].isWhitespace()) i++
                    return i
                }
            }
            from = k + needle.length
        }
    }

    /** 把任意字符串包成合法 JSON 字符串字面量（含控制字符转义）。保真，不截断。 */
    internal fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
