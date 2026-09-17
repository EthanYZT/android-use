package com.androiduse.agent

/**
 * 最小的递归下降 JSON 解析器，用来读回 transcript.jsonl。
 *
 * 为什么不用 org.json：Android 自带的 org.json 在 JVM 单测里是桩实现，调用会抛异常，
 * 而日志读回、Markdown 导出正是最该单测的纯逻辑。
 *
 * 返回值：对象 → Map<String, Any?>（保持插入顺序），数组 → List<Any?>，字符串 → String，
 * 整数 → Long，小数 → Double，布尔 → Boolean，null → null。语法错误返回 null（不抛异常）。
 */
object MiniJson {

    fun parse(text: String): Any? = try {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) null else v
    } catch (_: Exception) {
        null
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            if (i >= s.length) throw IllegalStateException("eof")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw IllegalStateException("bad literal")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (s[i] != '"') throw IllegalStateException("key")
                val k = str()
                skipWs()
                if (s[i] != ':') throw IllegalStateException("colon")
                i++
                skipWs()
                m[k] = value()
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw IllegalStateException("obj sep")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw IllegalStateException("arr sep")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (true) {
                if (i >= s.length) throw IllegalStateException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val n = s[i++]
                        when (n) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalStateException("bad escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(start, i)
            if (t.isEmpty() || t == "-") throw IllegalStateException("number")
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
        }
    }
}
