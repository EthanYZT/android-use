package com.androiduse.capability

import java.net.URLEncoder

/** 地图 App 偏好序：裸 `geo:` 在本机会弹选择器（高德/百度/滴滴都接），所以指定第一个已安装的。 */
object MapApps {
    val PREFERRED: List<Pair<String, String>> = listOf(
        "com.autonavi.minimap" to "高德地图",
        "com.baidu.BaiduMap" to "百度地图",
        "com.tencent.map" to "腾讯地图",
    )
    fun preferred(installed: Collection<String>): String? = PREFERRED.firstOrNull { it.first in installed }?.first
    fun label(pkg: String?): String = PREFERRED.firstOrNull { it.first == pkg }?.second ?: "地图"
}

/**
 * Intent 类系统调用 → `am start` argv（给 [com.androiduse.root.RootShell.execArgv]，不经 shell 二次解析）。
 * 一律 `--display <虚拟屏> -f 0x18000000`（NEW_TASK|MULTIPLE_TASK，与 open_app 同理：否则 singleTask 的
 * Activity 复用物理屏旧实例、忽略 --display）。Provider 类调用返回 null。
 */
object SystemIntents {
    const val FLAGS = "0x18000000"
    const val SMS_PACKAGE = "com.android.mms"

    fun argv(call: SystemCall, displayId: Int, mapPackage: String?): List<String>? {
        val base = listOf("am", "start", "--display", displayId.toString(), "-f", FLAGS)
        return when (call) {
            is SystemCall.SetAlarm -> base + listOf(
                "-a", "android.intent.action.SET_ALARM",
                "--ei", "android.intent.extra.alarm.HOUR", call.hour.toString(),
                "--ei", "android.intent.extra.alarm.MINUTES", call.minute.toString(),
                "--ez", "android.intent.extra.alarm.SKIP_UI", "true",
            ) + (call.label?.let { listOf("--es", "android.intent.extra.alarm.MESSAGE", it) } ?: emptyList())
            is SystemCall.SmsCompose -> base + listOf(
                "-a", "android.intent.action.SENDTO", "-d", "smsto:${call.number}",
                "--es", "sms_body", call.body, "-p", SMS_PACKAGE,
            )
            is SystemCall.Dial -> base + listOf("-a", "android.intent.action.DIAL", "-d", "tel:${call.number}")
            is SystemCall.Navigate -> base + listOf("-a", "android.intent.action.VIEW", "-d", "geo:0,0?q=${encodeQuery(call.query)}") +
                (mapPackage?.let { listOf("-p", it) } ?: emptyList())
            is SystemCall.OpenSettings -> base + listOf("-a", call.page.action)
            is SystemCall.CalendarQuery, is SystemCall.CalendarCreate, is SystemCall.CalendarUpdate, is SystemCall.ContactsLookup -> null
        }
    }

    /** 成功时回给模型的文案。Intent 只是"发出去了"，措辞不承诺目标 App 的结果。 */
    fun successText(call: SystemCall, mapPackage: String?): String = when (call) {
        is SystemCall.SetAlarm -> "已请求时钟设置 %02d:%02d 闹钟；要核对可 open_app 时钟".format(call.hour, call.minute)
        is SystemCall.SmsCompose -> "已打开短信编辑页，收件人与正文已填，尚未发送"
        is SystemCall.Dial -> "已打开拨号盘并填入号码，未拨出"
        is SystemCall.Navigate -> if (mapPackage != null) "已在${MapApps.label(mapPackage)}打开 ${call.query}"
            else "已打开地图 ${call.query}（系统弹出了选择器，需要点一个地图 App）"
        is SystemCall.OpenSettings -> "已打开 ${call.page.label} 设置页"
        is SystemCall.CalendarQuery, is SystemCall.CalendarCreate, is SystemCall.CalendarUpdate, is SystemCall.ContactsLookup -> ""
    }

    /** URL 编码，空格用 %20（URLEncoder 默认的 + 在 geo 查询里会被当字面加号）。 */
    fun encodeQuery(q: String): String = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
}
