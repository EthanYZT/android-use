package com.androiduse.agent

/**
 * 一个可被 open_app 启动的 App：[label] 是桌面显示名（模型看到并用来点名的），
 * [component] 是 `包名/Activity` 形式的启动组件（由 PackageManager 解析得到，模型永远看不到）。
 */
data class AppEntry(val label: String, val component: String)

/**
 * open_app 的名字解析与提示词列表。纯逻辑。
 *
 * 设计取舍（2026-09-18 决定，方案 A）：模型**按显示名**点 App，列表由我们在任务开始时用
 * PackageManager 查好放进系统提示。这样模型不用猜 OnePlus 的 `com.oplus.*` 包名，
 * 列表本身也是天然白名单——不在列表里的 App 无法被启动。
 */
object AppCatalog {

    /**
     * 模型给的名字 → App。匹配顺序：
     *  1. 忽略大小写与首尾空白的**精确**匹配；
     *  2. 否则，名字是**唯一一个** App 显示名的子串时放行（模型少打了字）；
     *  3. 多个 App 都包含该名字且没有精确匹配 → null（不瞎猜，让模型改）。
     * 空名字 → null。
     */
    fun resolve(name: String, apps: List<AppEntry>): AppEntry? {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return null
        apps.firstOrNull { it.label.trim().lowercase() == q }?.let { return it }
        val partial = apps.filter { it.label.lowercase().contains(q) }
        return partial.singleOrNull()
    }

    /**
     * 发给模型的 App 名字列表，顿号分隔一行。显示名是第三方 App 可控的文字，
     * 一律经 [UntrustedText.sanitize]（控制字符 → 空格、截断），不得裸拼进提示词。
     */
    fun promptList(apps: List<AppEntry>): String =
        apps.joinToString("、") { UntrustedText.sanitize(it.label) }
}
