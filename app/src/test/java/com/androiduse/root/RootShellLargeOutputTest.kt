package com.androiduse.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 RootShell 的死锁/丢输出问题：子进程输出超过操作系统管道缓冲区
 * （通常 ~64KB）时，若不在 waitFor 之前并发排空 stdout，子进程会阻塞在
 * write() 上，进而被误报为超时且丢失全部输出。
 *
 * 用 `sh -c` 跑一条普通 shell 命令而不是 `su -c`，这样测试能在没有设备、
 * 没有 root 的构建机上直接跑；生产路径 [RootShell.exec] 只是在同一套
 * [RootShell.run] 核心逻辑外面套了一层 `su -c`。
 */
class RootShellLargeOutputTest {

    @Test
    fun exec_withOutputLargerThanPipeBuffer_returnsCompleteOutputWithoutTimeout() {
        // 200KB，是常见 64KB 管道缓冲区的三倍多，足以在不并发排空时触发死锁。
        val result = RootShell.run(
            listOf("sh", "-c", "yes A | head -c 200000"),
            timeoutMs = 5_000,
        )

        assertTrue(
            "expected success but got exitCode=${result.exitCode} stderr=${result.stderr}",
            result.ok,
        )

        val expected = "A\n".repeat(100_000).trim()
        assertEquals(expected.length, result.stdout.length)
        assertEquals(expected, result.stdout)
    }
}
