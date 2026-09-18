package com.androiduse.power

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.androiduse.root.RootShell

/**
 * 后台运行豁免（root 自授，与 2a 的 `pm grant` 同一路数）。
 *
 * 2026-09-18 真机（ColorOS 16，HANS 冻结器）：只开前台服务不够——App 切后台 5 秒仍被冻结（StrictMode-3）并拉进
 * 网络黑名单，模型请求挂死、切回前台时中止。把本包加进 Doze 白名单（`dumpsys deviceidle whitelist +pkg`，
 * 等价于用户在设置里点"不优化电池"）后 HANS 降到 StrictMode-1：不再断网，冻结也会被到达的网络包解冻，
 * 任务能在后台跑完。白名单持久化在 /data/system/deviceidle.xml，加一次即可；这里每次任务开始检查一下。
 */
object BackgroundExemption {
    private const val TAG = "BackgroundExemption"

    fun whitelistArgv(pkg: String): List<String> = listOf("dumpsys", "deviceidle", "whitelist", "+$pkg")

    /** 已在白名单返回 true；否则 root 加入并复查。阻塞（root shell），在 IO 线程调用。 */
    fun ensure(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        val pkg = context.packageName
        if (pm.isIgnoringBatteryOptimizations(pkg)) return true
        val r = RootShell.execArgv(whitelistArgv(pkg))
        val ok = pm.isIgnoringBatteryOptimizations(pkg)
        Log.i(TAG, "deviceidle whitelist +$pkg exit=${r.exitCode} nowExempt=$ok ${r.stdout.trim().take(80)}")
        return ok
    }
}
