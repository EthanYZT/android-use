package com.androiduse.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellResultTest {
    @Test
    fun exitCodeZeroMeansOk() {
        assertTrue(ShellResult(0, "uid=0(root)", "").ok)
    }

    @Test
    fun nonZeroExitCodeMeansNotOk() {
        assertFalse(ShellResult(1, "", "su: not found").ok)
    }
}
