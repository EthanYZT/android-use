package com.androiduse.power

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundExemptionTest {
    @Test fun whitelistArgvAddsThePackageToTheDozeWhitelist() {
        assertEquals(listOf("dumpsys", "deviceidle", "whitelist", "+com.androiduse"), BackgroundExemption.whitelistArgv("com.androiduse"))
    }
}
