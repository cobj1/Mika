package com.wangzan101.mika.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevDeviceCheckTest {
    private val gib = 1024L * 1024 * 1024
    @Test fun downloadPreflightRejectsUnsupportedAndInsufficientResources() {
        assertEquals(JevDeviceCheck.Status.Unsupported, JevDeviceCheck.assess(false, false, 8*gib, 8*gib, 2*gib))
        assertEquals(JevDeviceCheck.Status.LowMemory, JevDeviceCheck.assess(true, true, 8*gib, 8*gib, 2*gib))
        assertEquals(JevDeviceCheck.Status.LowMemory, JevDeviceCheck.assess(true, false, 3*gib-1, 8*gib, 2*gib))
        assertEquals(JevDeviceCheck.Status.LowStorage, JevDeviceCheck.assess(true, false, 3*gib, 2*gib, 2*gib))
    }
    @Test fun resumedDownloadOnlyRequiresRemainingBytesAndReserve() {
        val remaining = gib / 4
        assertEquals(JevDeviceCheck.Status.Ready, JevDeviceCheck.assess(true, false, 3*gib, remaining + JevDeviceCheck.STORAGE_RESERVE, remaining))
        assertEquals(JevDeviceCheck.Status.LowStorage, JevDeviceCheck.assess(true, false, 3*gib, remaining + JevDeviceCheck.STORAGE_RESERVE - 1, remaining))
    }
    @Test fun installedModelAdmissionChecksAvailableMemoryWithoutDownloadSpace() {
        assertFalse(JevDeviceCheck.memoryReady(false, 3*gib-1))
        assertFalse(JevDeviceCheck.memoryReady(true, 8*gib))
        assertTrue(JevDeviceCheck.memoryReady(false, 3*gib))
        // 已安装模型运行时不需要再为下载留空间，下载准入仍独立检查空间。
        assertEquals(JevDeviceCheck.Status.LowStorage,
            JevDeviceCheck.assess(true, false, 3*gib, 0, 0))
    }
}
