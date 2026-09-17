package com.androiduse.root

import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/** 一次 shell 调用的结果。纯数据，可单测。 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * 通过 su 执行命令。本机 root 由 SukiSU(KernelSU) 提供，本 App 需在 SukiSU 的
 * 超级用户列表里被授权，否则 `su` 会报 not found。
 *
 * 每次调用起一个新的 su 进程：阶段 0 的调用频率低（秒级），进程开销可以接受，
 * 换来的是不用维护长连接的状态机。后续若成为瓶颈再改成常驻 su 会话。
 */
object RootShell {

    fun exec(command: String, timeoutMs: Long = 15_000): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellResult(-1, "", "timeout after ${timeoutMs}ms: $command")
            }
            val out = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val err = process.errorStream.bufferedReader().use(BufferedReader::readText)
            ShellResult(process.exitValue(), out.trim(), err.trim())
        } catch (e: Exception) {
            ShellResult(-1, "", "exec failed: ${e.message}")
        }
    }

    /** 探测 root 是否可用。返回 true 表示拿到了 uid=0。 */
    fun isRootAvailable(): Boolean {
        val r = exec("id")
        return r.ok && r.stdout.contains("uid=0")
    }
}
