package com.androiduse.daemon

import android.app.UiAutomation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 把某个逻辑屏上的所有窗口节点树，扁平成一串 [DumpCodec.NodeRecord]。
 *
 * 为什么扁平而不保留层级：1c 的 grounding 让模型「按元素编号选」，一个带 bounds/text/id 的
 * 平铺列表正是它需要的，也最省 token。层级信息对定位不是必需的（bounds 已足够定位）。
 *
 * 过滤：只收「对操作有意义」的节点——可点/可滚，或带 text/contentDescription。纯布局容器
 * （无文字、不可点）会爆列表且对模型无用，丢弃。这与 DESIGN §5.1 ①级「拿坐标」的用途一致。
 */
object NodeExtractor {

    /** 抽取给定屏上的节点。屏上无窗口则返回空列表。 */
    fun extractForDisplay(ua: UiAutomation, displayId: Int): List<DumpCodec.NodeRecord> {
        val all = ua.windowsOnAllDisplays
        val windows: List<AccessibilityWindowInfo> = all.get(displayId) ?: emptyList()
        val out = ArrayList<DumpCodec.NodeRecord>()
        val counter = intArrayOf(0)
        for (w in windows) {
            val root = w.root ?: continue
            walk(root, out, counter)
        }
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<DumpCodec.NodeRecord>, counter: IntArray) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val clickable = node.isClickable
        val scrollable = node.isScrollable
        val editable = node.isEditable

        val interesting = clickable || scrollable || editable || text.isNotEmpty() || desc.isNotEmpty()
        if (interesting) {
            val b = Rect()
            node.getBoundsInScreen(b)
            out.add(
                DumpCodec.NodeRecord(
                    id = counter[0]++,
                    left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                    text = text,
                    desc = desc,
                    resId = node.viewIdResourceName.orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    clickable = clickable,
                    scrollable = scrollable,
                    editable = editable,
                )
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, counter)
        }
    }

    /**
     * 按 dump 时的编号找回节点：用与 [extractForDisplay] **完全相同**的遍历与过滤顺序重新数一遍。
     * 树若已变化，编号可能指向别的节点——调用方（set_text）只在紧接一次 dump 之后用它。
     */
    fun findNode(ua: UiAutomation, displayId: Int, id: Int): AccessibilityNodeInfo? {
        val windows = ua.windowsOnAllDisplays.get(displayId) ?: return null
        val counter = intArrayOf(0)
        for (w in windows) {
            val root = w.root ?: continue
            findInSubtree(root, id, counter)?.let { return it }
        }
        return null
    }

    private fun findInSubtree(node: AccessibilityNodeInfo, id: Int, counter: IntArray): AccessibilityNodeInfo? {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val interesting = node.isClickable || node.isScrollable || node.isEditable || text.isNotEmpty() || desc.isNotEmpty()
        if (interesting) { if (counter[0] == id) return node; counter[0]++ }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findInSubtree(child, id, counter)?.let { return it }
        }
        return null
    }

    /** 屏上当前有焦点的可编辑节点；没有则第一个可编辑节点；都没有 → null。 */
    fun findEditable(ua: UiAutomation, displayId: Int): AccessibilityNodeInfo? {
        val windows = ua.windowsOnAllDisplays.get(displayId) ?: return null
        var first: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isEditable) {
                if (n.isFocused) return n
                if (first == null) first = n
            }
            for (i in 0 until n.childCount) { walk(n.getChild(i) ?: continue)?.let { return it } }
            return null
        }
        for (w in windows) { walk(w.root ?: continue)?.let { return it } }
        return first
    }
}
