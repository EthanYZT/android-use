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

    fun perform(action: Action, screen: VirtualScreen): Boolean {
        if (action is Action.Finish) return true
        if (action is Action.Wait) {
            Thread.sleep(action.ms.toLong())
            return true
        }
        val cmd = ActionCommand.toShell(action, screen) ?: return true
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
