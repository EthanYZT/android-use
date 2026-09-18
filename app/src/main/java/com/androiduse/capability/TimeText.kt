package com.androiduse.capability

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * 模型给的时间字符串 ↔ epoch ms。只认 `YYYY-MM-DD HH:mm`（允许 T 分隔）与 `YYYY-MM-DD`，
 * STRICT 解析（2 月 30 日报错而不是悄悄变成 28 日）。自然语言（"明天下午"）不在这里处理：
 * 系统提示里给了当前时间，让模型自己换算成绝对时间。
 */
object TimeText {
    private val DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val WEEKDAYS = arrayOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** `2026-09-22 15:00` 或 `2026-09-22T15:00` → 该时区的 epoch ms；格式不对返回 null。 */
    fun parseDateTime(s: String, zone: ZoneId): Long? = try {
        LocalDateTime.parse(s.trim().replace('T', ' '), DATE_TIME).atZone(zone).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) { null }

    /** 全天事件：只取前 10 个字符当日期，存成 UTC 零点（CalendarContract 的全天约定）。 */
    fun parseDateUtc(s: String): Long? = try {
        LocalDate.parse(s.trim().take(10), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) { null }

    fun startOfDay(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun format(ms: Long, zone: ZoneId, pattern: String): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(pattern))

    /** 系统提示里的当前时间行用：`2026-09-18 16:52 星期五`。 */
    fun formatWithWeekday(ms: Long, zone: ZoneId): String {
        val z = Instant.ofEpochMilli(ms).atZone(zone)
        return z.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")) + " " + WEEKDAYS[z.dayOfWeek.value - 1]
    }
}
