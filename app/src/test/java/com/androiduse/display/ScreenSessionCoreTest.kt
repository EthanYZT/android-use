package com.androiduse.display

import com.androiduse.daemon.DaemonProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable

/** ScreenSession 的状态机：租约 → 建屏 → 起设置；失败回滚；探测失效重建。 */
class ScreenSessionCoreTest {

    private class Fake(
        var createOk: Boolean = true,
        var leaseOk: Boolean = true,
        var frameOk: Boolean = true,
    ) : DisplayService {
        val log = mutableListOf<String>()
        var leaseClosed = 0
        override fun openLease(): Closeable? { log += "lease"; return if (leaseOk) Closeable { leaseClosed++ } else null }
        override fun createDisplay(w: Int, h: Int, dpi: Int): DaemonProtocol.CreateResult {
            log += "create"
            return if (createOk) DaemonProtocol.CreateResult.Ok(9, w, h) else DaemonProtocol.CreateResult.Err("nope")
        }
        override fun destroyDisplay(): Boolean { log += "destroy"; return true }
        override fun frame(maxWidth: Int, quality: Int): DaemonProtocol.FrameResult =
            if (frameOk) DaemonProtocol.FrameResult.Empty else DaemonProtocol.FrameResult.Err("dead")
        override fun launchSettings(displayId: Int): Boolean { log += "settings$displayId"; return true }
    }

    private fun core(f: Fake) = ScreenSessionCore(f, settleMs = 0, sleep = {})

    @Test
    fun ensureOpensLeaseCreatesAndLaunchesSettingsInOrder() {
        val f = Fake(); val c = core(f)
        assertEquals(VirtualScreen(9, 1080, 2376), c.ensure())
        assertEquals(listOf("lease", "create", "settings9"), f.log)
    }

    @Test
    fun ensureIsIdempotentWhileDaemonAlive() {
        val f = Fake(); val c = core(f)
        val a = c.ensure(); val b = c.ensure()
        assertEquals(a, b)
        assertEquals(1, f.log.count { it == "create" })
    }

    @Test
    fun createFailureClosesLeaseAndReturnsNull() {
        val f = Fake(createOk = false); val c = core(f)
        assertNull(c.ensure())
        assertEquals(1, f.leaseClosed)
        assertNull(c.screen)
    }

    @Test
    fun leaseFailureReturnsNullWithoutCreating() {
        val f = Fake(leaseOk = false); val c = core(f)
        assertNull(c.ensure())
        assertFalse(f.log.contains("create"))
    }

    @Test
    fun destroyReleasesDisplayAndLease() {
        val f = Fake(); val c = core(f)
        c.ensure()
        assertTrue(c.destroy())
        assertNull(c.screen)
        assertEquals(1, f.leaseClosed)
        assertTrue(f.log.contains("destroy"))
    }

    @Test
    fun ensureAfterDaemonDeathRebuilds() {
        val f = Fake(); val c = core(f)
        c.ensure()
        f.frameOk = false          // 守护进程死了：探测失败
        val again = c.ensure()
        f.frameOk = true
        assertEquals(VirtualScreen(9, 1080, 2376), again)
        assertEquals("旧租约要关、屏要重建", 2, f.log.count { it == "create" })
        assertEquals(1, f.leaseClosed)
    }
}
