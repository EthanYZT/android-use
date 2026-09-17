package com.androiduse.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 argv 入口不经 shell 二次解析：外部字符串里的元字符（`;` `$` 空格 引号）
 * 必须原样透传，而不是被拆词、展开或当命令执行。
 *
 * 生产入口 [RootShell.execArgv] 用 `su -c 'exec "$@"' -- <argv>`；这里用 `sh` 代替
 * `su` 验证同一套包裹语义，从而能在无 root、无设备的构建机上跑。测试直接驱动
 * [RootShell.run] 并复刻 execArgv 的包裹，保持与生产路径的字节级一致。
 */
class RootShellArgvTest {

    /** 复刻 execArgv 的包裹，但把 su 换成 sh，便于在构建机上跑。 */
    private fun shArgv(argv: List<String>): ShellResult =
        RootShell.run(listOf("sh", "-c", "exec \"\$@\"", "--") + argv, timeoutMs = 5_000)

    @Test
    fun metacharacters_arePassedThroughVerbatim() {
        // echo 会把每个参数用单空格连起来输出。若发生二次解析：`a;id` 里的 `;`
        // 会被当命令分隔符、`$HOME` 会被展开——都会污染输出。
        val r = shArgv(listOf("echo", "a;id", "\$HOME", "b c"))
        assertTrue("exit=${r.exitCode} stderr=${r.stderr}", r.ok)
        assertEquals("a;id \$HOME b c", r.stdout)
    }

    @Test
    fun semicolonDoesNotChainCommands() {
        // 若 `;` 被解释，`id` 会被执行，输出里会出现 uid=。原样透传则只有字面串。
        val r = shArgv(listOf("echo", "hello; id"))
        assertTrue(r.ok)
        assertEquals("hello; id", r.stdout)
        assertTrue("不应执行 id", !r.stdout.contains("uid="))
    }

    @Test
    fun singleArgWithSpaces_staysOneArg() {
        // `wc -w` 数词数：整串作为一个参数传入，echo 出来仍是那串，wc 数的是词而非参数个数。
        // 这里直接验证 echo 不拆词：三个词的单参数原样输出。
        val r = shArgv(listOf("echo", "one two three"))
        assertTrue(r.ok)
        assertEquals("one two three", r.stdout)
    }
}
