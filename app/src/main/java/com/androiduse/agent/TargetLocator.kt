package com.androiduse.agent

import com.androiduse.daemon.DumpCodec.NodeRecord

/**
 * tap 的 target 形态：把"按描述找元素"变成 Jev 能答的问题，再把答案判成"点 / 不点"。**纯逻辑**，不碰网络。
 * spec `docs/superpowers/specs/2026-09-26-jev-target-grounding-design.md` §3.2、§4。
 *
 * 问法照搬 TypeSafe semantic_find cookbook：每个候选一行放进 state，Choice 的选项只是编号；
 * 另问一个 exists Noul——Choice 概率总和为 1，目标不存在时也总有一个排第一。
 * 位置给"下部-右"这类词而不是数字坐标：Jev 对数字判断弱（jaggedness 文档）。
 */
object TargetLocator {

    /** exists Noul 低于此值 → 判定屏幕上没有这个元素。 */
    const val EXISTS_MIN = 0.5
    /** where Choice 的 confidence 低于此值 → 判定不确定，不点。 */
    const val CONFIDENCE_MIN = 0.6
    /** Choice 选项上限 255，留余量。 */
    const val CHUNK_SIZE = 250
    /** 分块时每块进第二轮的名额。 */
    const val PER_CHUNK_KEEP = 2

    const val EMPTY_MESSAGE = "屏幕上没有可定位的元素，请用 x/y"

    data class Candidate(val node: NodeRecord, val xn: Int, val yn: Int, val line: String)
    data class JevRequest(val stateJson: String, val questionsJson: String)

    fun region(xn: Int, yn: Int): String {
        val row = when { yn < 333 -> "上部"; yn < 667 -> "中部"; else -> "下部" }
        val col = when { xn < 333 -> "左"; xn < 667 -> "中"; else -> "右" }
        return "$row-$col"
    }

    /**
     * 可定位的候选：bounds 不退化，且可点 / 可输入 / 有 text 或 desc（OCR 伪节点有文字，保留）。
     * 无文字的可点节点同一中心只留第一个（与 [NodeGrounding.selectForPrompt] 口径一致）。按阅读顺序返回。
     */
    fun candidates(nodes: List<NodeRecord>, screenW: Int, screenH: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        val seenBlankCenters = HashSet<Long>()
        for (n in nodes) {
            val c = NodeGrounding.centerNorm(n, screenW, screenH) ?: continue
            val labeled = n.text.isNotEmpty() || n.desc.isNotEmpty()
            if (!n.clickable && !n.editable && !labeled) continue
            if (!labeled && !n.editable && !seenBlankCenters.add(c.first.toLong() * 10_000 + c.second)) continue
            out += Candidate(n, c.first, c.second, line(n, c.first, c.second))
        }
        return out.sortedWith(compareBy({ it.yn }, { it.xn }, { it.node.id }))
    }

    internal fun resTail(resId: String): String = resId.substringAfter(":id/", resId)

    private fun line(n: NodeRecord, xn: Int, yn: Int): String {
        val sb = StringBuilder("#").append(n.id).append(' ').append(region(xn, yn))
        if (n.clickable) sb.append(" click")
        if (n.editable) sb.append(" edit")
        if (n.className == OcrMerge.OCR_CLASS) sb.append(" ocr")
        if (n.text.isNotEmpty()) sb.append(' ').append(UntrustedText.field("text", n.text))
        if (n.desc.isNotEmpty()) sb.append(' ').append(UntrustedText.field("desc", n.desc))
        val res = resTail(n.resId)
        if (res.isNotEmpty()) sb.append(' ').append(UntrustedText.field("res", res))
        return sb.toString()
    }

    /** 文案里指代一个元素：`#27 text="去结算"`，没文字时退到 desc、resId 末段、只有编号。 */
    fun label(n: NodeRecord): String {
        val res = resTail(n.resId)
        val f = when {
            n.text.isNotEmpty() -> UntrustedText.field("text", n.text)
            n.desc.isNotEmpty() -> UntrustedText.field("desc", n.desc)
            res.isNotEmpty() -> UntrustedText.field("res", res)
            else -> null
        }
        return if (f == null) "#${n.id}" else "#${n.id} $f"
    }

    private fun js(s: String) = PromptBuilder.jsonString(s)

    private fun state(cands: List<Candidate>) = """{"elements":[${cands.joinToString(",") { js(it.line) }}]}"""

    private fun where(target: String, cands: List<Candidate>): String {
        val instructions = "`elements` 是手机屏幕上的元素列表，每行以 #编号 开头，后面是它在屏幕上的位置和文字。要点击的元素是：「$target」。它是哪个编号？"
        val criteria = cands.joinToString(",") { "${js(it.node.id.toString())}:null" }
        return """{"type":"choice","instructions":${js(instructions)},"criteria":{$criteria}}"""
    }

    private fun exists(target: String): String {
        val instructions = "`elements` 里是否有这个元素：「$target」？"
        return """{"type":"noul","instructions":${js(instructions)},"criteria":{"true":${js("列表里有一个元素就是描述的那个")},"false":${js("列表里没有任何元素符合描述")}}}"""
    }

    fun firstRequest(target: String, cands: List<Candidate>): JevRequest {
        require(cands.isNotEmpty()) { "没有候选时不应请求 Jev" }
        val t = UntrustedText.sanitize(target)
        val chunks = cands.chunked(CHUNK_SIZE)
        val qs = ArrayList<String>()
        if (chunks.size == 1) qs += "\"where\":" + where(t, chunks[0])
        else chunks.forEachIndexed { i, c -> qs += "\"where_$i\":" + where(t, c) }
        qs += "\"exists\":" + exists(t)
        return JevRequest(state(cands), "{" + qs.joinToString(",") + "}")
    }

    fun secondRequest(target: String, finalists: List<Candidate>): JevRequest {
        val t = UntrustedText.sanitize(target)
        return JevRequest(state(finalists), "{\"where\":" + where(t, finalists) + "}")
    }
}
