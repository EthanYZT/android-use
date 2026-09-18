package com.androiduse.capability

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import java.util.TimeZone

/**
 * 日历 Provider 读写。查询走 Instances（展开重复事件，得到每次发生的 begin/end）；写走 Events。
 * 目标日历：第一个 `account_type=LOCAL` 的（本机 `_id=1 local account`），没有则第一个可见日历。
 */
class CalendarStore(private val cr: ContentResolver) {

    fun query(fromMs: Long, toMs: Long): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { ContentUris.appendId(it, fromMs) }.let { ContentUris.appendId(it, toMs) }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY,
        )
        val out = ArrayList<CalendarEvent>()
        cr.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                out += CalendarEvent(
                    id = c.getLong(0), title = c.getString(1) ?: "",
                    beginMs = c.getLong(2), endMs = c.getLong(3),
                    location = c.getString(4) ?: "", allDay = c.getInt(5) == 1,
                )
            }
        }
        return out
    }

    /** 返回新事件 id；没有可写日历返回 null。 */
    fun create(c: SystemCall.CalendarCreate): Long? {
        val calId = writableCalendarId() ?: return null
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, c.title)
            put(CalendarContract.Events.DTSTART, c.startMs)
            put(CalendarContract.Events.DTEND, c.endMs)
            put(CalendarContract.Events.ALL_DAY, if (c.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (c.allDay) "UTC" else TimeZone.getDefault().id)
            c.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }
        return cr.insert(CalendarContract.Events.CONTENT_URI, v)?.lastPathSegment?.toLongOrNull()
    }

    /** 成功返回改了哪些字段名；失败返回带原因的 failure（id 不存在 / 重复事件 / 更新行数不为 1）。 */
    fun update(c: SystemCall.CalendarUpdate): Result<List<String>> {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, c.id)
        val proj = arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.RRULE)
        var oldStart = 0L; var oldEnd: Long? = null; var rrule: String? = null; var found = false
        cr.query(uri, proj, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                found = true
                oldStart = cur.getLong(0)
                oldEnd = if (cur.isNull(1)) null else cur.getLong(1)
                rrule = cur.getString(2)
            }
        }
        if (!found) return Result.failure(IllegalArgumentException("没有 id=${c.id} 的事件，请先 calendar_query"))
        if (!rrule.isNullOrEmpty()) return Result.failure(IllegalArgumentException("id=${c.id} 是重复事件，暂不支持改期"))

        val changed = ArrayList<String>()
        val v = ContentValues()
        c.title?.let { v.put(CalendarContract.Events.TITLE, it); changed += "title" }
        c.location?.let { v.put(CalendarContract.Events.EVENT_LOCATION, it); changed += "location" }
        val newStart = c.startMs ?: oldStart
        val newEnd = c.endMs ?: if (c.startMs != null) newStart + ((oldEnd ?: (oldStart + 3_600_000L)) - oldStart) else oldEnd
        if (c.startMs != null) { v.put(CalendarContract.Events.DTSTART, newStart); changed += "start" }
        if (c.endMs != null || c.startMs != null) { v.put(CalendarContract.Events.DTEND, newEnd); if (c.endMs != null) changed += "end" }
        if (newEnd != null && newEnd <= newStart) return Result.failure(IllegalArgumentException("end 必须晚于 start（原事件时长换算后不合法，请同时给 end）"))
        val n = cr.update(uri, v, null, null)
        return if (n == 1) Result.success(changed) else Result.failure(IllegalStateException("更新影响了 $n 行"))
    }

    private fun writableCalendarId(): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.VISIBLE)
        var first: Long? = null
        cr.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, CalendarContract.Calendars._ID + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getString(1) == CalendarContract.ACCOUNT_TYPE_LOCAL) return id
                if (first == null && c.getInt(2) == 1) first = id
            }
        }
        return first
    }
}
