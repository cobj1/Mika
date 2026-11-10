package com.wangzan101.mika.cap.phone.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupAutomationWindowLeaseTest {
    @Test fun unreadablePageIsBoundToTheFirstDeveloperWindowAfterExplicitLaunch() {
        val lease = SetupAutomationWindowLease("settings", 100)
        assertFalse(lease.accepts(10, "Other", null, 200))
        assertTrue(lease.accepts(11, "开发者选项", null, 200))
        assertFalse(lease.accepts(12, "开发者选项", null, 300))
        assertTrue(lease.accepts(11, "开发者选项", null, 20_000))
    }
    @Test fun knownOtherPackageCannotUseATitleToMasqueradeAsSettings() {
        val lease = SetupAutomationWindowLease("settings", 0)
        assertFalse(lease.accepts(10, "Developer options", "other", 100))
        assertTrue(lease.accepts(10, "Developer options", "settings", 100))
        assertFalse(lease.accepts(10, "Developer options", "other", 200))
    }
    @Test fun staleLaunchAndExpiredSessionCannotAuthorizeUnknownWindows() {
        val lease = SetupAutomationWindowLease("settings", 0)
        assertFalse(lease.accepts(10, "开发者选项", null, 15_001))
        assertTrue(lease.accepts(10, "开发者选项", "settings", 20_000))
        assertFalse(lease.accepts(10, "开发者选项", null, 90_001))
        assertFalse(lease.accepts(-1, "开发者选项", "settings", 500))
    }
}
