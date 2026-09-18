package com.androiduse.daemon

/**
 * 屏的租约（spec 1e §4.1）：App 建屏后开一条 LocalSocket 连接一直不关，守护进程读到 EOF 即销屏。
 * 同一时间最多一份；第二份到来时替换第一份，旧连接之后的 EOF 不能误销新租约的屏——用代际号区分。
 */
class LeaseRegistry {
    private var current: Long = 0L
    private var next: Long = 1L

    /** 新租约替换旧租约，返回本租约的代际号。 */
    @Synchronized fun open(): Long { current = next++; return current }

    /** 某租约的连接结束。只有它仍是当前租约时返回 true（调用方据此销屏）。 */
    @Synchronized fun close(token: Long): Boolean {
        if (current == 0L || token != current) return false
        current = 0L
        return true
    }

    @Synchronized fun hasLease(): Boolean = current != 0L
}
