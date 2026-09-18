package com.androiduse.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class TimeTextTest {
    private val sh = ZoneId.of("Asia/Shanghai")

    @Test fun parsesLocalDateTime() {
        // 2026-09-22 15:00 +08:00 = 2026-09-22T07:00Z
        assertEquals(1790060400000L, TimeText.parseDateTime("2026-09-22 15:00", sh))
        assertEquals(1790060400000L, TimeText.parseDateTime(" 2026-09-22T15:00 ", sh))
    }

    @Test fun rejectsLooseOrInvalidDates() {
        assertNull(TimeText.parseDateTime("2026-9-22 15:00", sh))
        assertNull(TimeText.parseDateTime("2026-02-30 10:00", sh))
        assertNull(TimeText.parseDateTime("明天下午三点", sh))
        assertNull(TimeText.parseDateTime("2026-09-22", sh))
    }

    @Test fun rejectsYearThatParsesButOverflowsEpochMillis() {
        // STRICT 模式下带显式正号的极端年份能通过 LocalDateTime.parse，但换算 epoch ms 会
        // ArithmeticException: long overflow；模型给的任意字符串不能崩掉 App，必须是 null。
        assertNull(TimeText.parseDateTime("+999999999-01-01 00:00", sh))
    }

    @Test fun allDayParsesAsUtcMidnightAndIgnoresTimePart() {
        assertEquals(1790121600000L, TimeText.parseDateUtc("2026-09-23"))
        assertEquals(1790121600000L, TimeText.parseDateUtc("2026-09-23 09:00"))
        assertNull(TimeText.parseDateUtc("2026-13-01"))
    }

    @Test fun startOfDayAndFormatRoundTrip() {
        val ms = TimeText.parseDateTime("2026-09-18 16:52", sh)!!
        assertEquals(TimeText.parseDateTime("2026-09-18 00:00", sh)!!, TimeText.startOfDay(ms, sh))
        assertEquals("09-18 16:52", TimeText.format(ms, sh, "MM-dd HH:mm"))
        assertEquals("2026-09-18 16:52 星期五", TimeText.formatWithWeekday(ms, sh))
    }
}
