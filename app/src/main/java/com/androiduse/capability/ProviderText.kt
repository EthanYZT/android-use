package com.androiduse.capability

import com.androiduse.agent.UntrustedText
import java.time.ZoneId
import java.time.ZoneOffset

/** 日历 Instances 查询的一行。全天事件的 beginMs/endMs 是 UTC 零点。 */
data class CalendarEvent(val id: Long, val title: String, val beginMs: Long, val endMs: Long, val location: String, val allDay: Boolean)

/** 联系人电话一条。type 是系统给的类型文案（手机/工作/…）。 */
data class ContactPhone(val name: String, val number: String, val type: String)

/**
 * Provider 结果 → tool 消息文本。title/location/姓名/号码/类型都是不可信数据，逐字段 [UntrustedText.sanitize]
 * （控制字符→空格、折叠空白、截断 60），不裸拼。
 */
object CalendarText {
    const val EMPTY = "这段时间没有事件"
    const val MAX_ROWS = 50

    fun rows(events: List<CalendarEvent>, zone: ZoneId): String {
        if (events.isEmpty()) return EMPTY
        val shown = events.take(MAX_ROWS).map { e ->
            val time = if (e.allDay) {
                TimeText.format(e.beginMs, ZoneOffset.UTC, "MM-dd") + " 全天"
            } else {
                val b = TimeText.format(e.beginMs, zone, "MM-dd HH:mm")
                val sameDay = TimeText.format(e.beginMs, zone, "MM-dd") == TimeText.format(e.endMs, zone, "MM-dd")
                b + "–" + TimeText.format(e.endMs, zone, if (sameDay) "HH:mm" else "MM-dd HH:mm")
            }
            val loc = UntrustedText.sanitize(e.location)
            "id=${e.id} $time ${UntrustedText.sanitize(e.title)}" + (if (loc.isEmpty()) "" else " @$loc")
        }
        val rest = events.size - shown.size
        return shown.joinToString("\n") + (if (rest > 0) "\n（还有 $rest 条未显示，请缩小时间范围）" else "")
    }

    fun created(id: Long): String = "已创建事件 id=$id"
    fun updated(id: Long, changed: List<String>): String = "已更新事件 id=$id（${changed.joinToString(",")}）"
}

object ContactsText {
    const val MAX_ROWS = 10

    fun rows(list: List<ContactPhone>, name: String): String {
        if (list.isEmpty()) return "没找到叫 ${UntrustedText.sanitize(name)} 的联系人"
        val shown = list.take(MAX_ROWS).map { "${UntrustedText.sanitize(it.name)} ${UntrustedText.sanitize(it.number)} (${UntrustedText.sanitize(it.type)})" }
        val rest = list.size - shown.size
        return shown.joinToString("\n") + (if (rest > 0) "\n（还有 $rest 条未显示，请把名字写全）" else "")
    }
}
