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
     */
    fun parseVirtualSurfaceFlingerId(dumpsysSfOutput: String): Long? =
        SF_VIRTUAL.find(dumpsysSfOutput)
            ?.groupValues?.get(1)
            ?.toULongOrNull()
            ?.toLong()
}
