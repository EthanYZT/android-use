package com.androiduse.root

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

    fun exec(command: String, timeoutMs: Long = 15_000): ShellResult =
        run(listOf("su", "-c", command), timeoutMs)

    /**
     * 带外部字符串参数的 root 调用入口。**任何来自模型/屏幕/网络的不可信字符串都必须走
     * 这里，绝不能拼进 [exec] 的 command。**
     *
     * 与 [exec] 的本质区别：`exec` 把整条命令交给 shell 解析，字符串里的 `;` `$` `|`
     * 空格、引号都会被二次解释——不可信字符串一旦流进去就是 root 命令注入原语。本方法用
     *
     *     su -c 'exec "$@"' -- arg0 arg1 arg2 ...
     *
     * `-c` 后面那段是**固定字面量**，不含任何用户数据；真正的参数全部落在 `$@` 位置参数上，
     * shell 不会对它们做拆词、变量展开或元字符解释——每个 [argv] 元素原样成为目标程序的一个
     * 参数。语义与 execve 直接传 argv 等价，只是借道 su 拿 root。
     *
     * `argv[0]` 是要执行的程序名（如 "input"、"am"），其余是它的参数。
     * 例：`execArgv(listOf("input", "-d", "7", "text", 不可信文本))`——不可信文本即便含
     * `;rm -rf /` 也只是 `input text` 的一个字面参数，不会被执行。
     *
     * 单测见 RootShellArgvTest（用 sh 代 su 验证同一包裹语义，可在无 root 构建机上跑）。
     */
    fun execArgv(argv: List<String>, timeoutMs: Long = 15_000): ShellResult =
        run(listOf("su", "-c", "exec \"\$@\"", "--") + argv, timeoutMs)

    /**
     * 真正跑进程的核心逻辑，接受任意 argv。生产代码通过 [exec]（拼 `su -c <string>`）或 [execArgv]（`su -c` + 固定包裹 + argv）调用；
     * 这里单独拆出来是为了让单测能在没有 su / 没有真机的情况下，用普通 shell 命令
     * 驱动同一套“并发排空 stdout/stderr 再 waitFor”的逻辑。
     *
     * 关键点：两个读取线程必须在 `waitFor` 之前启动，且必须在读取最终结果之前 join。
     * 子进程的 stdout/stderr 各自只有一个操作系统管道缓冲区（通常 64KB左右），如果不
     * 被并发读取，写满后子进程会阻塞在 write() 上无法退出——此时 waitFor 会一直等到
     * 超时，再被 destroyForcibly() 杀掉：一个本该成功、只是输出量大的命令（例如后续
     * 任务里的 screencap / uiautomator dump / dumpsys），就会被错误地报告成超时且丢失
     * 全部输出。
     */
    internal fun run(commandLine: List<String>, timeoutMs: Long): ShellResult {
        return try {
            val process = ProcessBuilder(commandLine).start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    stdoutBuilder.append(process.inputStream.bufferedReader().use { it.readText() })
                } catch (_: Exception) {
                    // destroyForcibly 之后管道会被异常关闭，读取线程忽略即可：
                    // 该分支只发生在超时路径，返回值本来就不包含这部分输出。
                }
            }
            val stderrThread = Thread {
                try {
                    stderrBuilder.append(process.errorStream.bufferedReader().use { it.readText() })
                } catch (_: Exception) {
                }
            }
            stdoutThread.start()
            stderrThread.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutThread.join(1_000)
                stderrThread.join(1_000)
                return ShellResult(-1, "", "timeout after ${timeoutMs}ms: ${commandLine.joinToString(" ")}")
            }

            // join 建立 happens-before 关系，确保读取线程写入的内容对当前线程可见。
            stdoutThread.join()
            stderrThread.join()

            ShellResult(process.exitValue(), stdoutBuilder.toString().trim(), stderrBuilder.toString().trim())
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
