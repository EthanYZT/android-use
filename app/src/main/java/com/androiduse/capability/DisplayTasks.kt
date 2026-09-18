package com.androiduse.capability

/**
 * `dumpsys activity activities` 纯文本解析：按屏归属的任务列表，自顶向下、首次出现顺序、去重。
 *
 * 只认第 0 列 `Display #N (activities from top to bottom):` 头之后、下一个第 0 列（不缩进）行——
 * 且不是另一个 `Display #` 头——出现之前的 `  * Task{… #id …}` 行。真机的 `dumpsys` 在
 * `ActivityTaskSupervisor state:`（第 0 列，无缩进）之后会把同样的任务树再列一遍（缩进更深），
 * 那部分必须被排除，否则每个 id 都会数出两份。
 */
object DisplayTasks {
    /**
     * 一个任务：id 加它所属的包名。[pkg] 从任务行里的 `A=<uid>:<pkg>`（根任务常见形态）取，
     * 拿不到再退而取 `I=<pkg>/<cls>`（嵌套任务，比如桌面 Launcher 的 home 任务下面那层）的
     * 包名部分；两个都没有（比如纯 `type=home` 的外层容器任务）就是 null。
     */
    data class Task(val id: Int, val pkg: String?)

    private val displayHeader = Regex("^Display #(\\d+)")
    private val taskLine = Regex("^\\s+\\* Task\\{\\w+ #(\\d+)")
    private val ownerA = Regex("A=[^:\\s]+:(\\S+)")
    private val ownerI = Regex("I=(\\S+?)/")

    /** dump → displayId → 该屏任务列表（自顶向下，首次出现顺序，按 id 去重）。 */
    fun parse(dump: String): Map<Int, List<Task>> {
        val result = LinkedHashMap<Int, LinkedHashMap<Int, Task>>()
        var currentDisplay: Int? = null
        for (line in dump.lineSequence()) {
            val headerMatch = displayHeader.find(line)
            if (headerMatch != null) {
                currentDisplay = headerMatch.groupValues[1].toInt()
                result.getOrPut(currentDisplay) { LinkedHashMap() }
                continue
            }
            // 第 0 列且不是 Display 头：当前归属结束（例如 `ActivityTaskSupervisor state:`）。
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                currentDisplay = null
                continue
            }
            val display = currentDisplay ?: continue
            val taskMatch = taskLine.find(line) ?: continue
            val id = taskMatch.groupValues[1].toInt()
            val tasks = result.getValue(display)
            if (id !in tasks) {
                val pkg = ownerA.find(line)?.groupValues?.get(1) ?: ownerI.find(line)?.groupValues?.get(1)
                tasks[id] = Task(id, pkg)
            }
        }
        return result.mapValues { it.value.values.toList() }
    }

    /**
     * 落到物理屏（display 0）需要撤回的任务 id：
     * - 新出现在 display 0 的任务（after − before，按 id）；[pkg] 非 null 时只保留包名等于
     *   它的（我们这次启动的包），排除同一时间物理屏上恰好自己弹出的别的东西；[pkg] 为 null
     *   表示这次启动的包解析不出来（比如 navigate 没装地图），这时任何新任务都算数——宁可
     *   撤回得多一点，也不要因为不知道包名就完全不核对。
     * - display 0 置顶任务发生了变化，且新置顶任务的包名匹配（或 [pkg] 未知）：这种任务本来
     *   就在 before 里、不算"新"，但从后台被带到最前本身就是"抢占物理屏前台"，同样要撤回。
     *
     * [ownPkg] 是本 App 自己的包名，永远从候选里排除——不管 [pkg]（这次启动的目标包）是否已知。
     * 本 App 自己就跑在物理屏上（阶段 0/2a 设计如此），它的任务在 display 0 置顶、或者作为
     * "新任务"出现，都是正常前台切换，从来不是"泄漏"；[pkg] 为 null 时尤其容易误判——不加
     * 这层排除，一次 navigate 因为没装地图而 [pkg] 未知，随后本 App 自己的任务恰好被带回置顶，
     * 就会被当成泄漏，进而尝试 `am stack remove` 自己的任务。
     */
    fun leakedToPhysical(before: Map<Int, List<Task>>, after: Map<Int, List<Task>>, pkg: String?, ownPkg: String): List<Int> {
        val beforeIds = (before[0] ?: emptyList()).map { it.id }.toSet()
        val afterTasks = after[0] ?: emptyList()
        val matches = { t: Task -> t.pkg != ownPkg && (pkg == null || t.pkg == pkg) }

        val result = LinkedHashSet<Int>()
        afterTasks.filter { it.id !in beforeIds }.filter(matches).forEach { result += it.id }

        val newTop = afterTasks.firstOrNull()
        val oldTopId = (before[0] ?: emptyList()).firstOrNull()?.id
        if (newTop != null && newTop.id != oldTopId && matches(newTop)) {
            result += newTop.id
        }
        return result.toList()
    }
}
