package com.androiduse.actuation

import com.androiduse.display.VirtualScreen
import com.androiduse.root.RootShell
import kotlin.random.Random

/**
 * 执行动作。Wait 走 sleep，Finish 直接返回成功。
 *
 * 时序抖动（spec §4.3②）：每次注入后额外等一小段随机时间。
 * 关键约束是**只加不减**——最小等待仍保证，不会因为抖动反而点太快。
 */
object Injector {

    private const val BASE_SETTLE_MS = 600L

    /** 起 App 比点一下慢得多：冷启动 + 首帧渲染，等久一点下一步截图才不是空白/启动页。 */
    private const val OPEN_APP_SETTLE_MS = 1500L

    fun perform(action: Action, screen: VirtualScreen): Boolean {
        if (action is Action.Finish) return true
        if (action is Action.OpenApp) {
            // am start 找不到组件或被系统拒绝时退出码非 0，ok=false 会作为"注入失败"反馈给模型。
            val ok = RootShell.execArgv(ActionCommand.openAppArgv(action, screen)).ok
            if (ok) Thread.sleep(jitter(OPEN_APP_SETTLE_MS))
            return ok
        }
        if (action is Action.Wait) {
            // F-1：再夹一次，防止未来出现不经 ResponseParser 构造 Action.Wait 的调用方
            // 重新把未夹紧的值捅到这里——解析边界已经夹过一次（ResponseParser），这里是
            // 纵深防御，不依赖调用方守规矩。
            Thread.sleep(Action.Wait.clamp(action.ms).toLong())
            return true
        }
        val cmd = ActionCommand.toShell(action, screen)
        if (cmd == null) {
            // F-6：默认失败关闭，不是失败开放。toShell 返回 null 有两种可能：未来新增
            // action 忘了在 toShell 里处理（bug），或者是像 Action.Home 那样故意拒绝
            // （见 ActionCommand.toShell 对 Home 的注释，F-3）。两种情况都不该被上报成
            // "注入成功"——调用方（AgentLoop）靠这个返回值判断世界状态是否真的被动过；
            // 谎报成功会让循环带着错误的假设继续往下跑。
            return false
        }
        val ok = RootShell.exec(cmd).ok
        if (ok) Thread.sleep(jitter(BASE_SETTLE_MS))
        return ok
    }

    /** 在 baseMs 上再加 0~40% 随机量。只加不减。 */
    fun jitter(baseMs: Long, fraction: Double = 0.4): Long {
        if (baseMs <= 0) return baseMs
        val extra = (baseMs * fraction).toLong().coerceAtLeast(1)
        return baseMs + Random.nextLong(0, extra + 1)
    }
}
