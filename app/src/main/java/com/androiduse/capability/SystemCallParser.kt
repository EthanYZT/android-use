package com.androiduse.capability

import com.androiduse.agent.ResponseParser
import java.time.ZoneId

/**
 * tool call 的 arguments（不可信 JSON）→ [SystemCall]。字段读取走 [ResponseParser] 的锚定式解析。
 * 错误文本是给模型看的：说清缺哪个字段、要什么格式，让它下一轮改。
 */
object SystemCallParser {

    sealed class Result {
        data class Ok(val call: SystemCall) : Result()
        data class Err(val message: String) : Result()
    }

    val TOOL_NAMES: Set<String> = setOf(
        "set_alarm", "calendar_query", "calendar_create", "calendar_update",
        "contacts_lookup", "sms_compose", "dial", "navigate", "open_settings",
    )

    const val TIME_HINT = "格式 YYYY-MM-DD HH:mm（例如 2026-09-22 15:00）"
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
    private val PHONE = Regex("^\\+?[0-9]{3,20}$")

    /** 非系统工具名返回 null，由调用方走原有分支。 */
    fun parse(name: String, argumentsJson: String, nowMs: Long, zone: ZoneId): Result? {
        val a = argumentsJson
        fun str(k: String): String? = ResponseParser.field(a, k)?.trim()?.takeIf { it.isNotEmpty() }
        fun err(m: String) = Result.Err(m)
        return when (name) {
            "set_alarm" -> {
                val h = ResponseParser.intField(a, "hour") ?: return err("set_alarm 需要 hour（0-23）")
                val m = ResponseParser.intField(a, "minute") ?: 0
                if (h !in 0..23) return err("hour 必须在 0-23")
                if (m !in 0..59) return err("minute 必须在 0-59")
                Result.Ok(SystemCall.SetAlarm(h, m, str("label")))
            }
            "calendar_query" -> {
                val fromRaw = str("from"); val toRaw = str("to")
                val from = if (fromRaw == null) TimeText.startOfDay(nowMs, zone)
                    else TimeText.parseDateTime(fromRaw, zone) ?: return err("from $TIME_HINT")
                val to = if (toRaw == null) from + 7 * DAY_MS
                    else TimeText.parseDateTime(toRaw, zone) ?: return err("to $TIME_HINT")
                if (to <= from) return err("to 必须晚于 from")
                Result.Ok(SystemCall.CalendarQuery(from, to))
            }
            "calendar_create" -> {
                val title = str("title") ?: return err("calendar_create 需要 title")
                val startRaw = str("start") ?: return err("calendar_create 需要 start，$TIME_HINT")
                val allDay = ResponseParser.field(a, "all_day").equals("true", ignoreCase = true)
                if (allDay) {
                    val s = TimeText.parseDateUtc(startRaw) ?: return err("全天事件的 start 格式 YYYY-MM-DD")
                    return Result.Ok(SystemCall.CalendarCreate(title, s, s + DAY_MS, str("location"), true))
                }
                val s = TimeText.parseDateTime(startRaw, zone) ?: return err("start $TIME_HINT")
                val endRaw = str("end")
                val e = if (endRaw == null) s + HOUR_MS else TimeText.parseDateTime(endRaw, zone) ?: return err("end $TIME_HINT")
                if (e <= s) return err("end 必须晚于 start")
                Result.Ok(SystemCall.CalendarCreate(title, s, e, str("location"), false))
            }
            "calendar_update" -> {
                val id = ResponseParser.field(a, "id")?.trim()?.toLongOrNull() ?: return err("calendar_update 需要 id（calendar_query 结果里的 id=N）")
                val title = str("title"); val location = str("location")
                val startRaw = str("start"); val endRaw = str("end")
                val s = startRaw?.let { TimeText.parseDateTime(it, zone) ?: return err("start $TIME_HINT") }
                val e = endRaw?.let { TimeText.parseDateTime(it, zone) ?: return err("end $TIME_HINT") }
                if (title == null && location == null && s == null && e == null) return err("calendar_update 至少要给 title/start/end/location 中的一个")
                if (s != null && e != null && e <= s) return err("end 必须晚于 start")
                Result.Ok(SystemCall.CalendarUpdate(id, title, s, e, location))
            }
            "contacts_lookup" -> {
                val n = str("name") ?: return err("contacts_lookup 需要 name")
                Result.Ok(SystemCall.ContactsLookup(n))
            }
            "sms_compose" -> {
                val num = phone(str("number")) ?: return err("sms_compose 需要 number，号码只能是数字（可带 +），3-20 位")
                val body = str("body") ?: return err("sms_compose 需要 body（短信正文）")
                Result.Ok(SystemCall.SmsCompose(num, body))
            }
            "dial" -> {
                val num = phone(str("number")) ?: return err("dial 需要 number，号码只能是数字（可带 +），3-20 位")
                Result.Ok(SystemCall.Dial(num))
            }
            "navigate" -> {
                val q = str("query") ?: return err("navigate 需要 query（地点名或地址）")
                Result.Ok(SystemCall.Navigate(q))
            }
            "open_settings" -> {
                val key = str("page") ?: return err("open_settings 需要 page，可选：${SettingsPage.keys()}")
                val page = SettingsPage.byKey(key) ?: return err("没有名为 $key 的设置页，可选：${SettingsPage.keys()}")
                Result.Ok(SystemCall.OpenSettings(page))
            }
            else -> null
        }
    }

    /** 去掉空格与 `-`，只接受 `+` 开头可选、3-20 位数字。 */
    fun phone(raw: String?): String? {
        val s = raw?.replace(" ", "")?.replace("-", "")?.trim() ?: return null
        return if (PHONE.matches(s)) s else null
    }
}
