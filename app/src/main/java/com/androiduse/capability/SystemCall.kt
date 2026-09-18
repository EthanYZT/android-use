package com.androiduse.capability

/** 副作用标签（DESIGN §8.1 的子集）。2a 只打标不接门，阶段 3 的安全网关按它拦截。 */
enum class SideEffect { NONE, DEVICE_STATE, SHARED }

/**
 * 系统接口工具的一次调用，字段都已经过 [SystemCallParser] 校验：时间是 epoch ms、号码只剩 `+` 和数字、
 * 设置页是枚举。执行侧（Intent argv / ContentResolver）不再需要防御模型输入。
 */
sealed class SystemCall {
    abstract val sideEffect: SideEffect

    data class SetAlarm(val hour: Int, val minute: Int, val label: String?) : SystemCall() {
        override val sideEffect get() = SideEffect.DEVICE_STATE
    }
    data class CalendarQuery(val fromMs: Long, val toMs: Long) : SystemCall() {
        override val sideEffect get() = SideEffect.NONE
    }
    /** 全天事件时 startMs/endMs 是 UTC 零点（endMs = 次日零点）。 */
    data class CalendarCreate(val title: String, val startMs: Long, val endMs: Long, val location: String?, val allDay: Boolean) : SystemCall() {
        override val sideEffect get() = SideEffect.SHARED
    }
    /** 可选字段至少一个非 null；只给 startMs 不给 endMs 时执行侧保持原时长。 */
    data class CalendarUpdate(val id: Long, val title: String?, val startMs: Long?, val endMs: Long?, val location: String?) : SystemCall() {
        override val sideEffect get() = SideEffect.SHARED
    }
    data class ContactsLookup(val name: String) : SystemCall() {
        override val sideEffect get() = SideEffect.NONE
    }
    /** 只打开编辑页，不发送。 */
    data class SmsCompose(val number: String, val body: String) : SystemCall() {
        override val sideEffect get() = SideEffect.NONE
    }
    /** 只打开拨号盘，不拨出（用 DIAL 不用 CALL）。 */
    data class Dial(val number: String) : SystemCall() {
        override val sideEffect get() = SideEffect.NONE
    }
    data class Navigate(val query: String) : SystemCall() {
        override val sideEffect get() = SideEffect.NONE
    }
    data class OpenSettings(val page: SettingsPage) : SystemCall() {
        override val sideEffect get() = SideEffect.NONE
    }
}

/** open_settings 的枚举页。action 全部在 OnePlus Ace 5 上解析到具体 Activity（spec §6）。 */
enum class SettingsPage(val key: String, val action: String, val label: String) {
    WIFI("wifi", "android.settings.WIFI_SETTINGS", "WLAN"),
    BLUETOOTH("bluetooth", "android.settings.BLUETOOTH_SETTINGS", "蓝牙"),
    DISPLAY("display", "android.settings.DISPLAY_SETTINGS", "显示"),
    SOUND("sound", "android.settings.SOUND_SETTINGS", "声音"),
    BATTERY_SAVER("battery_saver", "android.settings.BATTERY_SAVER_SETTINGS", "省电"),
    APPS("apps", "android.settings.APPLICATION_SETTINGS", "应用管理"),
    DATE("date", "android.settings.DATE_SETTINGS", "日期与时间"),
    LOCATION("location", "android.settings.LOCATION_SOURCE_SETTINGS", "位置"),
    NOTIFICATION("notification", "android.settings.NOTIFICATION_SETTINGS", "通知"),
    ACCESSIBILITY("accessibility", "android.settings.ACCESSIBILITY_SETTINGS", "无障碍"),
    NFC("nfc", "android.settings.NFC_SETTINGS", "NFC"),
    STORAGE("storage", "android.settings.INTERNAL_STORAGE_SETTINGS", "存储"),
    SECURITY("security", "android.settings.SECURITY_SETTINGS", "安全"),
    INPUT_METHOD("input_method", "android.settings.INPUT_METHOD_SETTINGS", "输入法"),
    ABOUT("about", "android.settings.DEVICE_INFO_SETTINGS", "关于本机"),
    NETWORK("network", "android.settings.WIRELESS_SETTINGS", "网络"),
    HOME("home", "android.settings.SETTINGS", "设置首页");

    companion object {
        fun byKey(key: String): SettingsPage? = entries.firstOrNull { it.key == key.trim().lowercase() }
        fun keys(): String = entries.joinToString("、") { it.key }
    }
}
