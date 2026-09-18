package com.androiduse.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemIntentsTest {
    private val base = listOf("am", "start", "--display", "7", "-f", "0x18000000")

    @Test fun setAlarmSkipsUiAndCarriesLabel() {
        val argv = SystemIntents.argv(SystemCall.SetAlarm(7, 30, "起床"), 7, null)!!
        assertEquals(base, argv.take(6))
        assertTrue(argv.containsAll(listOf("-a", "android.intent.action.SET_ALARM")))
        assertEquals("7", argv[argv.indexOf("android.intent.extra.alarm.HOUR") + 1])
        assertEquals("30", argv[argv.indexOf("android.intent.extra.alarm.MINUTES") + 1])
        assertEquals("true", argv[argv.indexOf("android.intent.extra.alarm.SKIP_UI") + 1])
        assertEquals("起床", argv[argv.indexOf("android.intent.extra.alarm.MESSAGE") + 1])
        assertTrue(SystemIntents.argv(SystemCall.SetAlarm(7, 30, null), 7, null)!!.none { it == "android.intent.extra.alarm.MESSAGE" })
        assertEquals("已请求时钟设置 07:30 闹钟；要核对可 open_app 时钟", SystemIntents.successText(SystemCall.SetAlarm(7, 30, null), null))
    }

    @Test fun smsPinsPackageAndFillsBody() {
        val argv = SystemIntents.argv(SystemCall.SmsCompose("13800000000", "我晚点到"), 7, null)!!
        assertTrue(argv.containsAll(listOf("-a", "android.intent.action.SENDTO", "-d", "smsto:13800000000", "--es", "sms_body", "我晚点到", "-p", "com.android.mms")))
        assertTrue(SystemIntents.successText(SystemCall.SmsCompose("1", "x"), null).contains("尚未发送"))
    }

    @Test fun dialDoesNotCall() {
        val argv = SystemIntents.argv(SystemCall.Dial("10086"), 7, null)!!
        assertTrue(argv.containsAll(listOf("-a", "android.intent.action.DIAL", "-d", "tel:10086")))
        assertTrue(argv.none { it.contains("CALL") })
        assertTrue(SystemIntents.successText(SystemCall.Dial("10086"), null).contains("未拨出"))
    }

    @Test fun navigateEncodesQueryAndPinsPreferredMap() {
        val argv = SystemIntents.argv(SystemCall.Navigate("天安门 东门"), 7, "com.autonavi.minimap")!!
        assertEquals("geo:0,0?q=%E5%A4%A9%E5%AE%89%E9%97%A8%20%E4%B8%9C%E9%97%A8", argv[argv.indexOf("-d") + 1])
        assertEquals("com.autonavi.minimap", argv[argv.indexOf("-p") + 1])
        assertTrue(SystemIntents.argv(SystemCall.Navigate("x"), 7, null)!!.none { it == "-p" })
        assertEquals("已在高德地图打开 天安门 东门", SystemIntents.successText(SystemCall.Navigate("天安门 东门"), "com.autonavi.minimap"))
        assertEquals("已打开地图 x（系统弹出了选择器，需要点一个地图 App）", SystemIntents.successText(SystemCall.Navigate("x"), null))
    }

    @Test fun mapPreferenceOrder() {
        assertEquals("com.autonavi.minimap", MapApps.preferred(setOf("com.baidu.BaiduMap", "com.autonavi.minimap")))
        assertEquals("com.baidu.BaiduMap", MapApps.preferred(setOf("com.baidu.BaiduMap", "com.sdu.didi.psnger")))
        assertNull(MapApps.preferred(setOf("com.sdu.didi.psnger")))
        assertEquals("百度地图", MapApps.label("com.baidu.BaiduMap"))
    }

    @Test fun settingsUsesEnumAction() {
        val argv = SystemIntents.argv(SystemCall.OpenSettings(SettingsPage.WIFI), 7, null)!!
        assertEquals(base + listOf("-a", "android.settings.WIFI_SETTINGS"), argv)
        assertEquals("已打开 WLAN 设置页", SystemIntents.successText(SystemCall.OpenSettings(SettingsPage.WIFI), null))
    }

    @Test fun providerCallsHaveNoIntent() {
        assertNull(SystemIntents.argv(SystemCall.CalendarQuery(0, 1), 7, null))
        assertNull(SystemIntents.argv(SystemCall.ContactsLookup("x"), 7, null))
    }
}
