package com.androiduse

import android.content.Intent
import android.content.pm.PackageManager
import com.androiduse.agent.AppEntry

/**
 * 查桌面可启动的 App（MAIN + LAUNCHER），转成 open_app 用的 [AppEntry]。
 * 显示名是第三方可控文字，进提示词前由 AppCatalog 统一净化，这里只做原样收集。
 * 同一 App 可能注册多个 launcher Activity（会得到相同显示名不同组件），按组件去重、按名字排序，
 * 保证同一台机器上列表稳定，模型两次任务看到的顺序一致。
 */
object LauncherApps {
    fun query(pm: PackageManager): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), "${it.activityInfo.packageName}/${it.activityInfo.name}") }
            .distinctBy { it.component }
            .sortedBy { it.label }
    }
}
