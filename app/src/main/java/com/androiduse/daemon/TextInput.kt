package com.androiduse.daemon

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 文字输入（type 动作）：不可见虚拟屏上输入法窗口不会弹出，`input text` 又不支持中文，
 * 所以用守护进程持有的 UiAutomation 直接对可编辑节点做 ACTION_SET_TEXT——支持任意文字、不依赖输入法、
 * 任何屏都能用。submit=true 时再发 ACTION_IME_ENTER（API 30+），触发搜索/确认。
 */
object TextInput {

    sealed class Result {
        data object Ok : Result()
        data class Err(val message: String) : Result()
    }

    fun setText(ua: UiAutomation, displayId: Int, nodeId: Int?, text: String, submit: Boolean): Result {
        val node: AccessibilityNodeInfo = (if (nodeId != null) NodeExtractor.findNode(ua, displayId, nodeId) else NodeExtractor.findEditable(ua, displayId))
            ?: return Result.Err(if (nodeId != null) "id $nodeId 的节点已找不到" else "屏幕上没有可输入的文本框")
        val target = if (node.isEditable) node else findEditableWithin(node)
            ?: return Result.Err("id $nodeId 不是可输入的文本框（也没有可输入的子节点）")
        if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return Result.Err("ACTION_SET_TEXT 被拒绝")
        if (submit) {
            try { ua.waitForIdle(200L, 1000L) } catch (_: Throwable) {}
            val ok = if (Build.VERSION.SDK_INT >= 30)
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false
            if (!ok) return Result.Err("文字已写入，但提交（IME_ENTER）被拒绝；可改为点击搜索按钮")
        }
        return Result.Ok
    }

    /** 模型点名的可能是包着输入框的容器：向下找第一个可编辑子节点。 */
    private fun findEditableWithin(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (c.isEditable) return c
            findEditableWithin(c)?.let { return it }
        }
        return null
    }
}
