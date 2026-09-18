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

    /**
     * 已知跳板 → 真正的会话 Activity。键是 `resolveActivity` 得到的 `flattenToString()`（长格式），
     * 值是 `am start -n` 用的组件。真机实测（2026-09-18 OnePlus Ace 5）：SENDTO 解析到
     * `LaunchConversationActivity`，它自己用 `NEW_TASK|NEW_DOCUMENT|CLEAR_TASK` 二次起
     * `ConversationActivity`，二次启动的新任务不认我们传的 `--display`，落到物理屏 0。
     * 已知跳板直接起目标 Activity 可以绕开这一步、把任务留在虚拟屏。
     *
     * ⚠️ 代价（同日真机复测确认）：直起不预填。跳板本来会先建一条会话记录、带着 `conversation_id`
     * 再启动 `ConversationActivity`；直接 `am start -n` 没有这个 `conversation_id`，`-a SENDTO -d smsto:…`
     * `--es sms_body …`、以及试过的 `address`/`android.intent.extra.TEXT` 都不生效——本机（ColorOS 15）
     * 打开的是一个空白新建页，收件人和正文都要在界面里手动填。保留这些参数是因为其它 ROM 可能会认，
     * 但 `successText` 必须如实告诉模型"没预填、要用 type 补"，不能再说"已填"。
     */
    val SMS_TRAMPOLINES: Map<String, String> = mapOf(
        "com.android.mms/com.android.mms.ui.conversation.LaunchConversationActivity" to "com.android.mms/.ui.conversation.ConversationActivity",
    )

    /** resolved 为已知跳板时返回应直接启动的组件，否则 null（走 `-p` 交给系统解析）。 */
    fun smsComponentFor(resolved: String?): String? = resolved?.let { SMS_TRAMPOLINES[it] }

    fun argv(call: SystemCall, displayId: Int, mapPackage: String?, smsComponent: String? = null): List<String>? {
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
                "--es", "sms_body", call.body,
            ) + (if (smsComponent != null) listOf("-n", smsComponent) else listOf("-p", SMS_PACKAGE))
            is SystemCall.Dial -> base + listOf("-a", "android.intent.action.DIAL", "-d", "tel:${call.number}")
            is SystemCall.Navigate -> base + listOf("-a", "android.intent.action.VIEW", "-d", "geo:0,0?q=${encodeQuery(call.query)}") +
                (mapPackage?.let { listOf("-p", it) } ?: emptyList())
            is SystemCall.OpenSettings -> base + listOf("-a", call.page.action)
            is SystemCall.CalendarQuery, is SystemCall.CalendarCreate, is SystemCall.CalendarUpdate, is SystemCall.ContactsLookup -> null
        }
    }

    /**
     * 成功时回给模型的文案。Intent 只是"发出去了"，措辞不承诺目标 App 的结果。
     * [smsComponent] 非 null 表示 `SmsCompose` 走了 [SMS_TRAMPOLINES] 直起（本机 ColorOS 15 实测不预填），
     * 文案必须如实说明、引导模型改用 type 手动填，不能再说"已填"。
     */
    fun successText(call: SystemCall, mapPackage: String?, smsComponent: String? = null): String = when (call) {
        is SystemCall.SetAlarm -> "已请求时钟设置 %02d:%02d 闹钟；要核对可 open_app 时钟".format(call.hour, call.minute)
        is SystemCall.SmsCompose -> if (smsComponent != null)
            "已打开短信新建页，但本机会话页不预填：请用 type 在界面填入收件人 ${call.number} 和正文，再点发送"
            else "已打开短信编辑页，收件人与正文已填，尚未发送"
        is SystemCall.Dial -> "已打开拨号盘并填入号码，未拨出"
        is SystemCall.Navigate -> if (mapPackage != null) "已在${MapApps.label(mapPackage)}打开 ${call.query}"
            else "已打开地图 ${call.query}（系统弹出了选择器，需要点一个地图 App）"
        is SystemCall.OpenSettings -> "已打开 ${call.page.label} 设置页"
        is SystemCall.CalendarQuery, is SystemCall.CalendarCreate, is SystemCall.CalendarUpdate, is SystemCall.ContactsLookup -> ""
    }

    /** URL 编码，空格用 %20（URLEncoder 默认的 + 在 geo 查询里会被当字面加号）。 */
    fun encodeQuery(q: String): String = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
}
