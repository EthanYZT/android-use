package com.androiduse.capability

import android.Manifest
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
 */
class SystemInterfaces(private val context: Context, private val screen: VirtualScreen) {

    private companion object {
        const val TAG = "SystemInterfaces"
        const val INTENT_SETTLE_MS = 1500L
        val CALENDAR_READ = listOf(Manifest.permission.READ_CALENDAR)
        val CALENDAR_WRITE = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val CONTACTS_READ = listOf(Manifest.permission.READ_CONTACTS)
    }

    fun perform(call: SystemCall): SystemResult = try {
        when (call) {
            is SystemCall.SetAlarm, is SystemCall.SmsCompose, is SystemCall.Dial, is SystemCall.OpenSettings -> startIntent(call, null)
            is SystemCall.Navigate -> startIntent(call, MapApps.preferred(installedGeoHandlers()))
            is SystemCall.CalendarQuery -> withPerms(CALENDAR_READ) {
                SystemResult(true, CalendarText.rows(CalendarStore(context.contentResolver).query(call.fromMs, call.toMs), ZoneId.systemDefault()))
            }
            is SystemCall.CalendarCreate -> withPerms(CALENDAR_WRITE) {
                val id = CalendarStore(context.contentResolver).create(call)
                if (id == null) SystemResult(false, "没有可写的日历账户") else SystemResult(true, CalendarText.created(id))
            }
            is SystemCall.CalendarUpdate -> withPerms(CALENDAR_WRITE) {
                CalendarStore(context.contentResolver).update(call).fold(
                    onSuccess = { SystemResult(true, CalendarText.updated(call.id, it)) },
                    onFailure = { SystemResult(false, it.message ?: "更新失败") },
                )
            }
            is SystemCall.ContactsLookup -> withPerms(CONTACTS_READ) {
                SystemResult(true, ContactsText.rows(ContactsStore(context).lookup(call.name), call.name))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "system call failed: $call", e)
        SystemResult(false, "系统接口调用失败: ${e.toString().take(120)}")
    }

    private fun startIntent(call: SystemCall, mapPackage: String?): SystemResult {
        val argv = SystemIntents.argv(call, screen.logicalDisplayId, mapPackage) ?: return SystemResult(false, "不是 Intent 类调用")
        val r = RootShell.execArgv(argv)
        if (!r.ok) return SystemResult(false, "启动失败: ${(r.stderr.ifBlank { r.stdout }).take(120)}")
        Thread.sleep(Injector.jitter(INTENT_SETTLE_MS))
        return SystemResult(true, SystemIntents.successText(call, mapPackage))
    }

    private inline fun withPerms(perms: List<String>, block: () -> SystemResult): SystemResult {
        val missing = PermissionGrant.ensure(context, perms)
        if (missing != null) return SystemResult(false, "缺少权限 ${missing.substringAfterLast('.')}，无法访问")
        return block()
    }

    private fun installedGeoHandlers(): Set<String> =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=x")), 0)
            .map { it.activityInfo.packageName }.toSet()
}
