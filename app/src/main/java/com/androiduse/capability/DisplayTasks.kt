package com.androiduse.capability

/**
 * `dumpsys activity activities` 纯文本解析：按屏归属的任务 id 列表，自顶向下、首次出现顺序、去重。
 *
 * 只认第 0 列 `Display #N (activities from top to bottom):` 头之后、下一个第 0 列（不缩进）行——
 * 且不是另一个 `Display #` 头——出现之前的 `  * Task{… #id …}` 行。真机的 `dumpsys` 在
 * `ActivityTaskSupervisor state:`（第 0 列，无缩进）之后会把同样的任务树再列一遍（缩进更深），
 * 那部分必须被排除，否则每个 id 都会数出两份。
 */
object DisplayTasks {
    private val displayHeader = Regex("^Display #(\\d+)")
    private val taskLine = Regex("^\\s+\\* Task\\{\\w+ #(\\d+)")

    /** dump → displayId → 该屏任务 id（自顶向下，首次出现顺序，去重）。 */
    fun parse(dump: String): Map<Int, List<Int>> {
        val result = LinkedHashMap<Int, LinkedHashSet<Int>>()
        var currentDisplay: Int? = null
        for (line in dump.lineSequence()) {
            val headerMatch = displayHeader.find(line)
            if (headerMatch != null) {
                currentDisplay = headerMatch.groupValues[1].toInt()
                result.getOrPut(currentDisplay) { LinkedHashSet() }
                continue
            }
            // 第 0 列且不是 Display 头：当前归属结束（例如 `ActivityTaskSupervisor state:`）。
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                currentDisplay = null
                continue
            }
            val display = currentDisplay ?: continue
            val taskMatch = taskLine.find(line) ?: continue
            result.getValue(display).add(taskMatch.groupValues[1].toInt())
        }
        return result.mapValues { it.value.toList() }
    }

    /** 新出现在 display 0 的任务 id（after − before），空列表即没泄漏。 */
    fun leakedToPhysical(before: Map<Int, List<Int>>, after: Map<Int, List<Int>>): List<Int> {
        val beforeIds = (before[0] ?: emptyList()).toSet()
        val afterIds = after[0] ?: emptyList()
        return afterIds.filter { it !in beforeIds }
    }
}
