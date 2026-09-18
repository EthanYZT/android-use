package com.androiduse.capability

import android.content.Context
import android.content.pm.PackageManager
import com.androiduse.root.RootShell

/**
 * 运行时权限自授：App 进程用 ContentResolver 需要 READ_CALENDAR 等 dangerous 权限，正常要弹框。
 * 本机有 root，直接 `pm grant <包名> <权限>`（一次生效，卸载前不用再授）。AgentCli 跑在 uid 0，
 * checkSelfPermission 天然通过，不会走到 grant。
 */
object PermissionGrant {
    /** 全部已授返回 null；否则返回第一个授不下来的权限名（给模型的失败文案用）。 */
    fun ensure(context: Context, perms: List<String>): String? {
        for (p in perms) {
            if (context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) continue
            RootShell.execArgv(listOf("pm", "grant", context.packageName, p))
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return p
        }
        return null
    }
}
