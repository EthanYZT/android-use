package com.androiduse.agent

import com.androiduse.daemon.DumpCodec.NodeRecord

/** 一行 OCR 文字，像素框（与屏幕/节点 bounds 同坐标系）。纯数据，不依赖 Android。 */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * OCR 补洞（spec 1d §4.2）：三级降级的②级。节点树是主力，OCR 只补它读不到的文字。
 *
 * 规则：一行 OCR 文字的**中心点**落在任一"带字节点"（text 或 desc 非空、bounds 非退化）框内 → 节点树已覆盖，
 * 丢弃；否则保留，伪装成 [NodeRecord]（className=[OCR_CLASS]，不可点，id 从 max(node.id)+1 按 (top,left) 续编）
 * 追加到节点列表末尾。这样解析、批内重定位、UntrustedText 净化、promptBlock 全部零改动复用。
 * 图片/容器节点没有字不算覆盖——相册里图片上的文字正是要补出来的。
 */
object OcrMerge {

    const val OCR_CLASS = "ocr"

    /** 最多补入多少条，防止一屏小字把提示词撑爆（整个列表还受 NodeGrounding.MAX_NODES_IN_PROMPT 约束）。 */
    const val MAX_OCR_ENTRIES = 40

    fun merge(nodes: List<NodeRecord>, lines: List<OcrLine>): List<NodeRecord> {
        if (lines.isEmpty()) return nodes
        val covering = nodes.filter { (it.text.isNotBlank() || it.desc.isNotBlank()) && it.right > it.left && it.bottom > it.top }
        val kept = lines.asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() && it.text.any { c -> c.isLetterOrDigit() } }
            .filter { l ->
                val cx = (l.left + l.right) / 2; val cy = (l.top + l.bottom) / 2
                covering.none { n -> cx in n.left until n.right && cy in n.top until n.bottom }
            }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .take(MAX_OCR_ENTRIES)
            .toList()
        if (kept.isEmpty()) return nodes
        var nextId = (nodes.maxOfOrNull { it.id } ?: -1) + 1
        return nodes + kept.map { l ->
            NodeRecord(nextId++, l.left, l.top, l.right, l.bottom, l.text, "", "", OCR_CLASS, false, false)
        }
    }
}
