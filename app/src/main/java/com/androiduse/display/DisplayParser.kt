package com.androiduse.display

/**
 * 解析 dumpsys 输出，提取两套互不通用的 display id。纯函数，不碰 Android。
 *
 * - 逻辑 displayId：小整数，用于 `am start --display` 与 `input -d`
 * - SurfaceFlinger display id：20 位无符号长整数，只用于 `screencap -d`
 *
 * 两者混用会静默失败（命令返回 0 但什么也没发生），所以分开解析、分开传。
 */
object DisplayParser {

    private val LOGICAL_ID = Regex("""mDisplayId=(\d+)""")
    private val SF_VIRTUAL = Regex("""Display\s+(\d+)\s+\(Virtual display\)""")

    /** `dumpsys display` 里出现的全部逻辑 displayId，去重升序。 */
    fun parseLogicalDisplayIds(dumpsysDisplayOutput: String): List<Int> =
        LOGICAL_ID.findAll(dumpsysDisplayOutput)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .sorted()
            .toList()

    /**
     * `dumpsys SurfaceFlinger --display-id` 里虚拟屏那一行的 id。没有虚拟屏返回 null。
     *
     * 该 id 超出 Long 的有符号范围，用 toULong 解析后再转 Long 保留位模式；
     * screencap 接收的是同样的十进制字符串，所以回传时用 toULong().toString()。
     *
     * 只取 dump 顺序里的第一个——这是历史上唯一被用到的语义（配合 [parseLogicalDisplayIds]
     * 靠"数量收敛到 1"来判断"没有残留虚拟屏"）。F-5 之后新建屏的配对不应该再依赖"第一个"，
     * 应该用 [parseVirtualSurfaceFlingerIds] 做建屏前后的集合差，见 VirtualDisplayManager。
     */
    fun parseVirtualSurfaceFlingerId(dumpsysSfOutput: String): Long? =
        parseVirtualSurfaceFlingerIds(dumpsysSfOutput).firstOrNull()

    /**
     * `dumpsys SurfaceFlinger --display-id` 里全部虚拟屏 id，按 dump 出现顺序（不去重排序，
     * 因为调用方要拿它跟"建屏前的快照"做集合差，顺序/重复本身不重要，完整集合才重要）。
     *
     * F-5：[VirtualDisplayManager.create] 原来对逻辑 id 做建屏前后的集合差来找"新出现的那个"，
     * 却对 SurfaceFlinger id 只取 dump 里的第一个——两边不对称。如果设备上恰好已经有一块
     * "没有对应逻辑屏"的孤儿虚拟屏（SF 层残留但逻辑层已经没有），"第一个"可能命中它而不是
     * 真正新建的那块，产出一个两个 id 描述不同屏的 [VirtualScreen]（正是这个设计要防的
     * 静默错配）。有了这个列表版本，调用方可以像逻辑 id 一样做前后集合差，两层对称。
     */
    fun parseVirtualSurfaceFlingerIds(dumpsysSfOutput: String): List<Long> =
        SF_VIRTUAL.findAll(dumpsysSfOutput)
            .mapNotNull { it.groupValues[1].toULongOrNull()?.toLong() }
            .toList()
}
