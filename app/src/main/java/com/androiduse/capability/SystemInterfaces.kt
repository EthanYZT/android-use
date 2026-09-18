package com.androiduse.capability

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.androiduse.actuation.Injector
import com.androiduse.agent.SystemResult
import com.androiduse.display.VirtualScreen
import com.androiduse.root.RootShell
import java.time.ZoneId

/**
 * 系统接口的 Android 实现：Intent 类 → `am start --display <虚拟屏>`（root argv），
 * Provider 类 → ContentResolver（权限不足先 root `pm grant`）。任何异常都变成 ok=false 的文本回给模型，
 * 不抛到 AgentLoop。
 *
 * [providersAvailable]：CLI（AgentCli，裸 root app_process 的 systemMain Context）调 Provider 会被
 * `SecurityException: Unable to find app for caller` 拒绝——那是系统进程没有"调用者 App"身份，不是
 * 权限问题，`pm grant` 救不了。传 false 时四个 Provider 分支直接返回明确失败文案，不碰 ContentResolver；
 * App 内调用方保持默认 true。
 */
class SystemInterfaces(
    private val context: Context,
    private val screen: VirtualScreen,
    private val providersAvailable: Boolean = true,
) {

    private companion object {
        const val TAG = "SystemInterfaces"
        const val INTENT_SETTLE_MS = 1500L
        const val PROVIDERS_UNAVAILABLE_TEXT = "CLI 不支持日历/联系人工具，请用 App 进程跑此任务，或改用界面操作"
        val CALENDAR_READ = listOf(Manifest.permission.READ_CALENDAR)
        val CALENDAR_WRITE = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val CONTACTS_READ = listOf(Manifest.permission.READ_CONTACTS)
    }

    fun perform(call: SystemCall): SystemResult = try {
        when (call) {
            is SystemCall.SetAlarm, is SystemCall.SmsCompose, is SystemCall.Dial, is SystemCall.OpenSettings -> startIntent(call, null)
            is SystemCall.Navigate -> startIntent(call, MapApps.preferred(installedGeoHandlers()))
            is SystemCall.CalendarQuery -> withProviders {
                withPerms(CALENDAR_READ) {
                    SystemResult(true, CalendarText.rows(CalendarStore(context.contentResolver).query(call.fromMs, call.toMs), ZoneId.systemDefault()))
                }
            }
            is SystemCall.CalendarCreate -> withProviders {
                withPerms(CALENDAR_WRITE) {
                    val id = CalendarStore(context.contentResolver).create(call)
                    if (id == null) SystemResult(false, "没有可写的日历账户") else SystemResult(true, CalendarText.created(id))
                }
            }
            is SystemCall.CalendarUpdate -> withProviders {
                withPerms(CALENDAR_WRITE) {
                    CalendarStore(context.contentResolver).update(call).fold(
                        onSuccess = { SystemResult(true, CalendarText.updated(call.id, it)) },
                        onFailure = { SystemResult(false, it.message ?: "更新失败") },
                    )
                }
            }
            is SystemCall.ContactsLookup -> withProviders {
                withPerms(CONTACTS_READ) {
                    SystemResult(true, ContactsText.rows(ContactsStore(context).lookup(call.name), call.name))
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "system call failed: $call", e)
        SystemResult(false, "系统接口调用失败: ${e.toString().take(120)}")
    }

    private fun startIntent(call: SystemCall, mapPackage: String?): SystemResult {
        val smsComponent = (call as? SystemCall.SmsCompose)?.let { SystemIntents.smsComponentFor(resolveSmsHandler(it.number)) }
        val argv = SystemIntents.argv(call, screen.logicalDisplayId, mapPackage, smsComponent) ?: return SystemResult(false, "不是 Intent 类调用")
        val before = DisplayTasks.parse(dumpActivities())
        val r = RootShell.execArgv(argv)
        if (!r.ok) return SystemResult(false, "启动失败: ${(r.stderr.ifBlank { r.stdout }).take(120)}")
        Thread.sleep(Injector.jitter(INTENT_SETTLE_MS))
        // 落屏核对：对所有 Intent 类工具生效。dumpsys 拿不到输出时 parse 得空 map，
        // leakedToPhysical 自然为空——不因核对本身失败而拦截启动，但要留一条日志。
        val leaked = DisplayTasks.leakedToPhysical(before, DisplayTasks.parse(dumpActivities()))
        if (leaked.isNotEmpty()) {
            leaked.forEach { RootShell.execArgv(listOf("am", "stack", "remove", it.toString())) } // 尽力撤回，不看结果
            Log.w(TAG, "intent leaked to display 0: tasks=$leaked call=$call")
            return SystemResult(false, "页面落到了物理屏而不是虚拟屏，已撤回；请改用界面操作完成")
        }
        return SystemResult(true, SystemIntents.successText(call, mapPackage))
    }

    private fun dumpActivities(): String = RootShell.execArgv(listOf("dumpsys", "activity", "activities")).stdout

    /** SENDTO 解析到的真实处理者（长格式 flattenToString），已知跳板时 [SystemIntents.smsComponentFor] 用它换直起的目标组件。 */
    private fun resolveSmsHandler(number: String): String? =
        context.packageManager.resolveActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).setPackage(SystemIntents.SMS_PACKAGE), 0)
            ?.activityInfo?.let { ComponentName(it.packageName, it.name).flattenToString() }

    private inline fun withProviders(block: () -> SystemResult): SystemResult =
        if (!providersAvailable) SystemResult(false, PROVIDERS_UNAVAILABLE_TEXT) else block()

    private inline fun withPerms(perms: List<String>, block: () -> SystemResult): SystemResult {
        val missing = PermissionGrant.ensure(context, perms)
        if (missing != null) return SystemResult(false, "缺少权限 ${missing.substringAfterLast('.')}，无法访问")
        return block()
    }

    private fun installedGeoHandlers(): Set<String> =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=x")), 0)
            .map { it.activityInfo.packageName }.toSet()
}
