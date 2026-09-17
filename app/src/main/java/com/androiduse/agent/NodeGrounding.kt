package com.androiduse.agent

import com.androiduse.actuation.Action
import com.androiduse.daemon.DumpCodec.NodeRecord

/**
 * 节点 grounding：把守护进程给的节点列表（[NodeRecord]，bounds 是虚拟屏像素）转成
 *  1. 发给模型的**提示词节点列表**（`promptBlock`），文字一律经 [UntrustedText]；
 *  2. 模型按 id 选中后，解析成一个归一化 [Action.Tap]（`resolveTapId`）。
 *
 * 全部纯逻辑，可单测。坐标口径与 §5/阶段 0 一致：模型看到和输出的都是 [0,1000] 归一化坐标，
 * 与截图/屏幕像素解耦；节点像素 bounds 在这里换算成归一化中心点。
 *
 * 退化 bounds：滚出屏幕的节点 getBoundsInScreen 会给出 right<=left 或 bottom<=top 的退化矩形
 * （spec §4 待处理 / DESIGN §5.2）。这类节点**不进提示词**（不给模型一个点不中的 id），若模型
 * 仍凭截图猜了这样一个 id，`resolveTapId` 返回 null（当作解析失败，让上层重试/中止）。
 */
object NodeGrounding {

    /** 提示词里最多列多少个节点，防止长页面把 token 撑爆。超出的靠截图视觉兜底。 */
    const val MAX_NODES_IN_PROMPT = 60

    /**
     * 节点中心的归一化坐标（[0,1000]）。屏幕尺寸非法或 bounds 退化时返回 null。
     */
    fun centerNorm(node: NodeRecord, screenW: Int, screenH: Int): Pair<Int, Int>? {
        if (screenW <= 0 || screenH <= 0) return null
        if (node.right <= node.left || node.bottom <= node.top) return null
        val cx = (node.left + node.right) / 2
        val cy = (node.top + node.bottom) / 2
        val xn = (cx.toLong() * 1000 / screenW).toInt().coerceIn(0, 1000)
        val yn = (cy.toLong() * 1000 / screenH).toInt().coerceIn(0, 1000)
        return xn to yn
    }

    /**
     * 构造发给模型的节点列表文本。每行：`#id (xn,yn) [click] text="..." desc="..."`。
     * 文字经 [UntrustedText.field]（净化+转义+截断），坐标是归一化中心。退化节点跳过。
     * 列表为空（无可用节点）返回空串，调用方据此走「仅截图」降级。
     */
    fun promptBlock(nodes: List<NodeRecord>, screenW: Int, screenH: Int): String {
        val sb = StringBuilder()
        var count = 0
        for (n in nodes) {
            if (count >= MAX_NODES_IN_PROMPT) break
            val c = centerNorm(n, screenW, screenH) ?: continue
            sb.append('#').append(n.id).append(" (").append(c.first).append(',').append(c.second).append(')')
            if (n.clickable) sb.append(" click")
            if (n.scrollable) sb.append(" scroll")
            if (n.text.isNotEmpty()) sb.append(' ').append(UntrustedText.field("text", n.text))
            if (n.desc.isNotEmpty()) sb.append(' ').append(UntrustedText.field("desc", n.desc))
            sb.append('\n')
            count++
        }
        return sb.toString().trimEnd()
    }

    /**
     * 把模型选中的节点 id 解析成一个归一化 [Action.Tap]（点在该节点中心）。
     * id 不存在或 bounds 退化 → null。
     */
    fun resolveTapId(id: Int, nodes: List<NodeRecord>, screenW: Int, screenH: Int): Action.Tap? {
        val node = nodes.firstOrNull { it.id == id } ?: return null
        val c = centerNorm(node, screenW, screenH) ?: return null
        return Action.Tap(c.first, c.second)
    }
}
