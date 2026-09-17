package com.androiduse.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RootShell.execArgv] 用 `su root <argv>`，KernelSU 的 su 会把 argv 直接 execve 给目标
 * 程序、不经任何 shell（2026-09-17 真机 v4.2.0 实测：`su root echo 'a;id'` 原样输出
 * `a;id`）。因此不可信字符串里的元字符（`;` `$` 空格 引号）不会被二次解析成命令。
 *
 * 构建机上没有 su，所以拆成两部分验证：
 *  1. [suArgv] 的前缀构造正确、不可信参数原样落在尾部、没有被拼进任何 shell 字符串；
 *  2. 用 [RootShell.run] 直接跑一个真实程序（execve 语义，无 shell），证明 argv 逐个原样
 *     透传——这正是 `su root` execve 之后目标程序看到的效果。
 *
 * ⚠️ 历史坑：最初写成 `su -c 'exec "$@"' -- <argv>`（标准 sh 的位置参数用法）。KernelSU 的
 * su 不转发位置参数，会把尾部参数拼进命令串重新交给 sh -c 解析，导致注入原语重新成立。
 * 见 RootShell.execArgv 的注释与 device-oneplus-ace5 记忆。
 */
class RootShellArgvTest {

    @Test
    fun suArgv_prependsSuRootAndKeepsArgsVerbatim() {
        val argv = listOf("input", "-d", "7", "text", "a;id \$HOME")
        assertEquals(
            listOf("su", "root", "input", "-d", "7", "text", "a;id \$HOME"),
            RootShell.suArgv(argv),
        )
    }

    @Test
    fun suArgv_untrustedArgIsAStandaloneElement_notConcatenated() {
        // 不可信参数必须是 argv 里独立的一个元素，而不是被拼进某个包含 shell 语法的字符串。
        val evil = "; rm -rf /"
        val out = RootShell.suArgv(listOf("echo", evil))
        assertEquals(evil, out.last())
        // 整个命令行里不能出现把 evil 包进去的 shell 包裹（如 exec "$@" 那种）。
        assertTrue("不应有 shell 包裹片段", out.none { it.contains("\$@") || it.contains("-c") })
    }

    @Test
    fun run_execvesWithoutShell_metacharsPassedVerbatim() {
        // run() 用 ProcessBuilder(argv) 直接 execve，无 shell——等价于 su root execve 之后
        // 目标程序看到的 argv。`;` 不该链式执行，`$HOME` 不该展开。
        val r = RootShell.run(listOf("echo", "a;id", "\$HOME", "b c"), timeoutMs = 5_000)
        assertTrue("exit=${r.exitCode} stderr=${r.stderr}", r.ok)
        assertEquals("a;id \$HOME b c", r.stdout)
        assertTrue("不应执行 id", !r.stdout.contains("uid="))
    }

    @Test
    fun run_singleArgWithSpaces_staysOneArg() {
        // 带空格的单个参数不被拆词：echo 原样输出。
        val r = RootShell.run(listOf("echo", "one two three"), timeoutMs = 5_000)
        assertTrue(r.ok)
        assertEquals("one two three", r.stdout)
    }
}
