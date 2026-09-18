package com.androiduse.actuation

import com.androiduse.display.VirtualScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些测试只覆盖 Injector.perform 在到达 RootShell.exec 之前就能确定结果的分支
 * （Finish / Wait / Home 的拒绝）——JVM 单测环境没有 su，一旦真的调用 RootShell.exec
 * 就会得到"exec failed"之类的失败结果，不适合在这里断言。
 */
class InjectorTest {

    private val screen = VirtualScreen(logicalDisplayId = 3, widthPx = 1080, heightPx = 2376)

    @Test
    fun finishAlwaysSucceedsWithoutTouchingShell() {
        assertTrue(Injector.perform(Action.Finish("done"), screen))
    }

    @Test
    fun homeFailsClosedInsteadOfReportingSuccess() {
        // F-3 + F-6: ActionCommand.toShell(Home, ...) 返回 null（拒绝），Injector 必须把
        // 这个 null 当成"没有真正执行"（false），不能像旧实现那样 `?: return true`
        // 默认成功——那样会让 AgentLoop 以为 HOME 被注入了，继续带着错误的世界状态往下跑。
        assertFalse(Injector.perform(Action.Home, screen))
    }

    @Test
    fun waitClampsNegativeMsInsteadOfThrowing() {
        // F-1 纵深防御：即便有调用方不经 ResponseParser 直接构造 Action.Wait(-1)，
        // Injector 自己也要夹紧，不能让 Thread.sleep(-1) 抛 IllegalArgumentException。
        // 负值夹到 0，Thread.sleep(0) 立即返回，测试不会变慢。
        assertTrue(Injector.perform(Action.Wait(-1), screen))
    }
}
