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

        val interesting = clickable || scrollable || text.isNotEmpty() || desc.isNotEmpty()
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
                )
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, counter)
        }
    }
}
