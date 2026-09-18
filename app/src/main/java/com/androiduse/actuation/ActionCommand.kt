package com.androiduse.actuation

import com.androiduse.display.VirtualScreen

/**
 * Action → shell 命令字符串。纯函数，可单测。
 *
 * 一律用逻辑 displayId（不是 SurfaceFlinger id），传错 input 会打到错误的屏。
 * Wait / Finish 不产生 shell 命令，返回 null 由调用方处理。
 *
 * F-6：null 是"这个动作不产生 shell 命令"的唯一信号，调用方（Injector.perform）必须
 * 把它当失败处理，而不是默认当成功——见 Action.Home 的拒绝就是靠这条契约生效的。
 */
object ActionCommand {

    private const val KEYCODE_BACK = 4

    fun toShell(action: Action, screen: VirtualScreen): String? {
        val d = screen.logicalDisplayId
        return when (action) {
            is Action.Tap -> {
                val x = CoordinateMapper.toPixels(action.xNorm, screen.widthPx)
                val y = CoordinateMapper.toPixels(action.yNorm, screen.heightPx)
                "input -d $d tap $x $y"
            }
            is Action.Swipe -> {
                val x1 = CoordinateMapper.toPixels(action.x1Norm, screen.widthPx)
                val y1 = CoordinateMapper.toPixels(action.y1Norm, screen.heightPx)
                val x2 = CoordinateMapper.toPixels(action.x2Norm, screen.widthPx)
                val y2 = CoordinateMapper.toPixels(action.y2Norm, screen.heightPx)
                "input -d $d swipe $x1 $y1 $x2 $y2 ${action.durationMs}"
            }
            Action.Back -> "input -d $d keyevent $KEYCODE_BACK"
            Action.Home ->
                // F-3：2026-09-17 真机实测证实，`input -d <虚拟屏id> keyevent 3` 不受 -d 隔离——
                // 它会把物理屏（display 0）从本 App 切到桌面 Launcher，直接抢占用户正在使用的
                // 物理前台，且 Tap/Swipe/Back 都不会这样。这是本项目"Agent 不能抢占物理前台"
                // 的红线，所以选择直接拒绝提供该动作，而不是伪造一层看似隔离实际没用的实现。
                // 拒绝点故意放在这里（Injector.perform 唯一的执行路径上），而不是只在上层
                // AgentLoop 里挡一下：任何绕开 AgentLoop、直接拿 Injector.perform 当入口的
                // 未来调用方（比如阶段 1 新的编排层）都无法绕过这个拒绝，因为它是靠构造本身
                // 保证的——toShell 对 Home 永远不产出命令。阶段 1 若做出真正按屏隔离的 Home，
                // 在这里恢复 `"input -d $d keyevent 3"` 即可，不需要动别处。
                null
            is Action.Wait -> null
            is Action.Finish -> null
            // OpenApp 只有 argv 形态（见 openAppArgv）；Injector 在走 toShell 之前单独处理它。
            is Action.OpenApp -> null
            // Type 走守护进程无障碍接口，不产生 shell 命令；Injector 单独处理。
            is Action.Type -> null
        }
    }

    /**
     * `am start --display <id> -n <component> -f 0x18000000`，以 argv 形态给 RootShell.execArgv。
     *
     * 0x18000000 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK（spec 1b 第 6 点）：
     * singleton/singleTask 的 Activity 若已在物理屏上有实例，`am start --display` 会复用旧实例
     * 而忽略目标屏；MULTIPLE_TASK 强制起新任务落到虚拟屏，物理屏上的实例不受影响。
     * 组件名虽由 PackageManager 给出，仍走 argv 不拼字符串，不给 shell 二次解析的机会。
     */
    fun openAppArgv(action: Action.OpenApp, screen: VirtualScreen): List<String> =
        listOf("am", "start", "--display", screen.logicalDisplayId.toString(), "-n", action.component, "-f", "0x18000000")
}
