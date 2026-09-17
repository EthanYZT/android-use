package com.androiduse.daemon

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.Looper

/**
 * 在 root `app_process` 里构造并连接一个 [UiAutomation]，用来读任意屏（含虚拟屏）的节点树。
 *
 * 全部走反射：`UiAutomationConnection` / `IUiAutomationConnection` / `UiAutomation(Looper,
 * IUiAutomationConnection)` / `connect()` / `disconnect()` 都是 @hide，App 编译期链不到。
 * 反射细节集中在这里，便于以后按 Android 版本适配（对应 spec §3 的 HiddenApi 集中点）。
 *
 * 关键前提（2026-09-17 真机 spike 验证，缺一不可）：
 *  1. 调用方进程**必须先 `Looper.prepareMainLooper()`**，否则 UiAutomation 回调线程里
 *     `AccessibilityInteractionClient.<init>` 会 `new Handler(getMainLooper())` NPE、进程被
 *     SIG 9 杀，表现为 connect 后莫名 "Killed"。这一步由 Daemon.main 负责，不在本类里。
 *  2. connect 后要开 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`，否则 getWindows* 返回空。
 *  3. 设备要解锁，否则 keyguard 遮挡、app 窗口对无障碍不可见。
 */
object UiAutomationFactory {

    /**
     * 构造并连接一个 UiAutomation（用给定 looper 承载它的回调）。失败抛异常由调用方处理。
     * 连接成功后已开好 interactive-windows flag。
     */
    fun connect(callbackLooper: Looper): UiAutomation {
        val connCls = Class.forName("android.app.UiAutomationConnection")
        val conn = connCls.getDeclaredConstructor().newInstance()
        val iConnCls = Class.forName("android.app.IUiAutomationConnection")

        val ctor = UiAutomation::class.java.getDeclaredConstructor(Looper::class.java, iConnCls)
        ctor.isAccessible = true
        val ua = ctor.newInstance(callbackLooper, conn) as UiAutomation

        UiAutomation::class.java.getDeclaredMethod("connect").apply {
            isAccessible = true
            invoke(ua)
        }

        // 开 interactive windows，否则 getWindowsOnAllDisplays 拿不到窗口。
        ua.serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            ua.serviceInfo = info
        }
        return ua
    }

    /** 断开连接。吞掉异常——断开失败不该影响已经拿到的结果。 */
    fun disconnect(ua: UiAutomation) {
        try {
            UiAutomation::class.java.getDeclaredMethod("disconnect").apply {
                isAccessible = true
                invoke(ua)
            }
        } catch (_: Throwable) {
        }
    }
}
