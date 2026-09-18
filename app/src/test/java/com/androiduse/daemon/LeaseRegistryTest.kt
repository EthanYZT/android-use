package com.androiduse.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同一时间最多一份租约；被替换的旧租约 EOF 不销屏，只有当前租约 EOF 才销屏。 */
class LeaseRegistryTest {

    @Test
    fun currentLeaseCloseTriggersDestroy() {
        val r = LeaseRegistry()
        val t = r.open()
        assertTrue(r.hasLease())
        assertTrue(r.close(t))
        assertFalse(r.hasLease())
    }

    @Test
    fun replacedLeaseCloseDoesNotTriggerDestroy() {
        val r = LeaseRegistry()
        val old = r.open()
        val new = r.open()
        assertFalse("旧租约 EOF 不能销掉新租约持有的屏", r.close(old))
        assertTrue(r.hasLease())
        assertTrue(r.close(new))
        assertFalse(r.hasLease())
    }

    @Test
    fun closingTwiceIsIdempotent() {
        val r = LeaseRegistry()
        val t = r.open()
        assertTrue(r.close(t))
        assertFalse(r.close(t))
    }

    @Test
    fun noLeaseInitially() {
        assertFalse(LeaseRegistry().hasLease())
    }
}
