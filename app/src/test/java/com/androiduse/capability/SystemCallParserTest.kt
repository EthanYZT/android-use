package com.androiduse.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SystemCallParserTest {
    private val sh = ZoneId.of("Asia/Shanghai")
    private val now = TimeText.parseDateTime("2026-09-18 16:52", sh)!!

    private fun ok(name: String, args: String): SystemCall =
        (SystemCallParser.parse(name, args, now, sh) as SystemCallParser.Result.Ok).call
    private fun err(name: String, args: String): String =
        (SystemCallParser.parse(name, args, now, sh) as SystemCallParser.Result.Err).message

    @Test fun unknownNameIsNullNotError() {
        assertNull(SystemCallParser.parse("tap", """{"id":1}""", now, sh))
    }

    @Test fun setAlarmValidatesRange() {
        assertEquals(SystemCall.SetAlarm(7, 30, "起床"), ok("set_alarm", """{"hour":7,"minute":30,"label":"起床"}"""))
        assertEquals(SystemCall.SetAlarm(7, 0, null), ok("set_alarm", """{"hour":7}"""))
        assertTrue(err("set_alarm", """{"hour":24,"minute":0}""").contains("hour"))
        assertTrue(err("set_alarm", """{"minute":5}""").contains("hour"))
    }

    @Test fun calendarQueryDefaultsToSevenDaysFromToday() {
        val c = ok("calendar_query", "{}") as SystemCall.CalendarQuery
        assertEquals(TimeText.parseDateTime("2026-09-18 00:00", sh)!!, c.fromMs)
        assertEquals(TimeText.parseDateTime("2026-09-25 00:00", sh)!!, c.toMs)
        val d = ok("calendar_query", """{"from":"2026-09-19 12:00","to":"2026-09-19 18:00"}""") as SystemCall.CalendarQuery
        assertEquals(TimeText.parseDateTime("2026-09-19 12:00", sh)!!, d.fromMs)
        assertTrue(err("calendar_query", """{"from":"2026-09-19 18:00","to":"2026-09-19 12:00"}""").contains("to"))
        assertTrue(err("calendar_query", """{"from":"明天"}""").contains("YYYY-MM-DD HH:mm"))
    }

    @Test fun calendarCreateDefaultsEndToOneHour() {
        val c = ok("calendar_create", """{"title":"周会","start":"2026-09-22 15:00","location":"3楼"}""") as SystemCall.CalendarCreate
        assertEquals("周会", c.title); assertEquals("3楼", c.location); assertEquals(false, c.allDay)
        assertEquals(c.startMs + 3_600_000L, c.endMs)
        assertTrue(err("calendar_create", """{"start":"2026-09-22 15:00"}""").contains("title"))
        assertTrue(err("calendar_create", """{"title":"x","start":"2026-09-22 15:00","end":"2026-09-22 14:00"}""").contains("end"))
    }

    @Test fun calendarCreateAllDayUsesUtcMidnights() {
        val c = ok("calendar_create", """{"title":"生日","start":"2026-09-23","all_day":true}""") as SystemCall.CalendarCreate
        assertEquals(true, c.allDay)
        assertEquals(TimeText.parseDateUtc("2026-09-23"), c.startMs)
        assertEquals(TimeText.parseDateUtc("2026-09-24"), c.endMs)
    }

    @Test fun calendarUpdateNeedsIdAndAtLeastOneField() {
        val c = ok("calendar_update", """{"id":12,"start":"2026-09-22 15:00"}""") as SystemCall.CalendarUpdate
        assertEquals(12L, c.id); assertNull(c.endMs); assertNull(c.title)
        assertTrue(err("calendar_update", """{"id":12}""").contains("至少"))
        assertTrue(err("calendar_update", """{"title":"x"}""").contains("id"))
    }

    @Test fun contactsLookupNeedsName() {
        assertEquals(SystemCall.ContactsLookup("张伟"), ok("contacts_lookup", """{"name":" 张伟 "}"""))
        assertTrue(err("contacts_lookup", """{"name":""}""").contains("name"))
    }

    @Test fun phoneNumbersAreNormalizedAndValidated() {
        assertEquals("+8613800000000", SystemCallParser.phone(" +86 138-0000-0000 "))
        assertNull(SystemCallParser.phone("12"))
        assertNull(SystemCallParser.phone("138abc"))
        assertNull(SystemCallParser.phone(null))
        assertEquals(SystemCall.Dial("10086"), ok("dial", """{"number":"10086"}"""))
        assertTrue(err("dial", """{"number":"张伟"}""").contains("号码"))
        assertEquals(SystemCall.SmsCompose("13800000000", "我晚点到"), ok("sms_compose", """{"number":"138 0000 0000","body":"我晚点到"}"""))
        assertTrue(err("sms_compose", """{"number":"10086"}""").contains("body"))
    }

    @Test fun navigateAndSettings() {
        assertEquals(SystemCall.Navigate("天安门"), ok("navigate", """{"query":"天安门"}"""))
        assertTrue(err("navigate", """{}""").contains("query"))
        assertEquals(SystemCall.OpenSettings(SettingsPage.WIFI), ok("open_settings", """{"page":"WiFi"}"""))
        val e = err("open_settings", """{"page":"wlan"}""")
        assertTrue(e, e.contains("wifi") && e.contains("bluetooth"))
    }

    @Test fun toolNamesCoverAllNine() {
        assertEquals(9, SystemCallParser.TOOL_NAMES.size)
    }
}
