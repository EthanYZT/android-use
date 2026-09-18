package com.androiduse

import android.app.Application
import com.androiduse.root.DaemonClient

/**
 * 进程级初始化：守护进程的 classpath（本 App 的 APK 路径）在这里注入，而不是在 MainActivity。
 * 否则从别的入口（设置页、外部 am start）先起就会撞 lateinit 未初始化直接崩（2026-09-18 真机撞过）。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DaemonClient.apkPath = applicationInfo.sourceDir
    }
}
