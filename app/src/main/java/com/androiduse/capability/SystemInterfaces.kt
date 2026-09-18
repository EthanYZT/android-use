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
        val launchedPkg = launchedPackage(call, mapPackage)
        val before = DisplayTasks.parse(dumpActivities())
        val r = RootShell.execArgv(argv)
        // r.ok 只看 exitCode；有些 ROM 上 Intent 解析失败（比如 -p 指定的包没装对应 Activity）
        // exitCode 仍是 0，错误只体现在 stdout 里一行 "Error: ..."，同样算启动失败。
        if (!r.ok || r.stdout.contains("Error:")) return SystemResult(false, "启动失败: ${(r.stderr.ifBlank { r.stdout }).take(120)}")
        Thread.sleep(Injector.jitter(INTENT_SETTLE_MS))
        // 落屏核对：对所有 Intent 类工具生效。dumpsys 拿不到输出（本次或起 Intent 之前那次）时对应
        // map 里没有 display 0 这个 key——核对本身跑不动，不因此拦截启动（ok=true），但文案要如实
        // 说明"没核对成"，日志留痕，否则「悄悄没查」和「查了确认没泄漏」没法区分。
        val after = DisplayTasks.parse(dumpActivities())
        if (0 !in before || 0 !in after) {
            Log.w(TAG, "display leak check unavailable (dumpsys failed): call=$call")
            return SystemResult(true, SystemIntents.successText(call, mapPackage, smsComponent) + "（未能核对页面是否落在虚拟屏）")
        }
        val leaked = DisplayTasks.leakedToPhysical(before, after, launchedPkg)
        if (leaked.isNotEmpty()) return rollback(leaked, call)
        return SystemResult(true, SystemIntents.successText(call, mapPackage, smsComponent))
    }

    /**
     * 泄漏撤回：对每个泄漏任务发 `am stack remove`，再重新 dump 一次核对是否真的撤下去了——
     * `ShellResult.ok` 只说明命令本身没报错，不代表任务真的没了（某些 ROM 的 `am stack remove`
     * 对已经 resumed 的任务是空操作）。核对方式是直接看这些任务 id 是否还在 display 0 的列表里
     * ——不能用 [DisplayTasks.leakedToPhysical] 重新判定"是不是泄漏"：那个函数只认"新出现"或
     * "刚好在置顶"，如果任务还在物理屏但既不新（早就在 before 里）也不再置顶，会被误判成"没事了"。
     * dumpsys 本身再次失败（拿不到 display 0）时保守按"还在"处理，不能因为查不到就报成功。
     * 两种结局都要 Log 留痕，且都是 ok=false：泄漏本身已经发生过（哪怕瞬间），不能因为事后
     * 撤回成功就告诉模型"什么都没发生"。
     */
    private fun rollback(leaked: List<Int>, call: SystemCall): SystemResult {
        val removeFailed = leaked.filterNot { RootShell.execArgv(listOf("am", "stack", "remove", it.toString())).ok }
        if (removeFailed.isNotEmpty()) {
            Log.w(TAG, "am stack remove reported failure: tasks=$removeFailed call=$call")
        }
        val recheck = DisplayTasks.parse(dumpActivities())
        val onDisplay0Now = (recheck[0] ?: emptyList()).map { it.id }.toSet()
        val stillPresent = if (0 in recheck) leaked.filter { it in onDisplay0Now } else leaked
        return if (stillPresent.isEmpty()) {
            Log.w(TAG, "intent leaked to display 0, rolled back: tasks=$leaked call=$call")
            SystemResult(false, "页面落到了物理屏而不是虚拟屏，已撤回；请改用界面操作完成")
        } else {
            Log.w(TAG, "intent leaked to display 0, rollback failed: tasks=$leaked stillPresent=$stillPresent call=$call")
            SystemResult(false, "页面落到了物理屏而不是虚拟屏，撤回失败（任务 #${stillPresent.joinToString(", ")} 仍在物理屏）；请改用界面操作完成")
        }
    }

    private fun dumpActivities(): String = RootShell.execArgv(listOf("dumpsys", "activity", "activities")).stdout

    /** SENDTO 解析到的真实处理者（长格式 flattenToString），已知跳板时 [SystemIntents.smsComponentFor] 用它换直起的目标组件。 */
    private fun resolveSmsHandler(number: String): String? =
        context.packageManager.resolveActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).setPackage(SystemIntents.SMS_PACKAGE), 0)
            ?.activityInfo?.let { ComponentName(it.packageName, it.name).flattenToString() }

    /**
     * 这次 Intent 调用会落到哪个包，用来把泄漏核对限定在"我们自己启动的东西"上：sms_compose
     * 固定打给短信 App；navigate 就是调用方选定的地图包（选不到时 null）；其余三种
     * （闹钟/拨号/设置页）用与实际启动相同的 action/data 交给系统解析，解析不到时 null——
     * 这时 [DisplayTasks.leakedToPhysical] 按"包名未知"处理，任何新任务都算数。
     */
    private fun launchedPackage(call: SystemCall, mapPackage: String?): String? = when (call) {
        is SystemCall.SmsCompose -> SystemIntents.SMS_PACKAGE
        is SystemCall.Navigate -> mapPackage
        is SystemCall.SetAlarm, is SystemCall.Dial, is SystemCall.OpenSettings -> resolveLaunchPackage(call)
        else -> null
    }

    private fun resolveLaunchPackage(call: SystemCall): String? {
        // action 字符串必须和 SystemIntents.argv 里实际用来起 Intent 的那个一致（Intent 类没有
        // ACTION_SET_ALARM 常量，那是 android.provider.AlarmClock 里的，这里直接用字面量）。
        val intent = when (call) {
            is SystemCall.SetAlarm -> Intent("android.intent.action.SET_ALARM")
            is SystemCall.Dial -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${call.number}"))
            is SystemCall.OpenSettings -> Intent(call.page.action)
            else -> return null
        }
        return context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }

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
