package com.wangzan101.mika.cap.phone.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SetupAutomationActionTest {
    private fun aboutPhone(uptime: String) = SetupAutomationTree(listOf(
        SetupNode(0),
        SetupNode(1, 0, clickable = true),
        SetupNode(2, 1, text = "Build 号"),
        SetupNode(3, 1, text = "V417IR release-keys"),
        SetupNode(4, 0, text = "正常运行时间"),
        SetupNode(5, 0, text = uptime),
    ))

    @Test fun uptimeTickDoesNotDispatchAnOldNodeAndNextSnapshotCanContinue() {
        val before = aboutPhone("1:06:39")
        val after = aboutPhone("1:06:40")
        var dispatched = 0
        assertEquals(SetupActionResult.Retry, SetupAutomationAction.apply(before, after, true) { dispatched++; true })
        assertEquals(0, dispatched)
        assertEquals(SetupAutomationAction.RetryReason.SnapshotChanged, SetupAutomationAction.retryReason(before, after, true))
        // 下一轮重新识别、重新取树。计时器刷新不等同于已下发的点击失败。
        assertEquals(1, after.click(SetupTarget.BuildNumber))
        assertEquals(SetupActionResult.Applied, SetupAutomationAction.apply(after, aboutPhone("1:06:40"), true) { dispatched++; true })
        assertEquals(1, dispatched)
    }

    @Test fun recycledPreferenceOrNewConfirmationNeverReceivesAnOldClick() {
        val before = aboutPhone("1:06:39")
        val replaced = SetupAutomationTree(before.nodes.map {
            if (it.id == 2) it.copy(text = "恢复出厂设置") else it
        })
        val confirmation = SetupAutomationTree(before.nodes + SetupNode(6, 0, resource = "android:id/button1", clickable = true))
        for (after in listOf(replaced, confirmation)) {
            var dispatched = false
            assertEquals(SetupActionResult.Retry, SetupAutomationAction.apply(before, after, true) { dispatched = true; true })
            assertFalse(dispatched)
        }
    }

    @Test fun anIdenticalTreeInAnotherWindowStillRequiresAnotherObservation() {
        val page = aboutPhone("1:06:39")
        var dispatched = false
        assertEquals(SetupActionResult.Retry, SetupAutomationAction.apply(page, page, false) { dispatched = true; true })
        assertFalse(dispatched)
        assertEquals(SetupAutomationAction.RetryReason.WindowChanged, SetupAutomationAction.retryReason(page, page, false))
    }

    @Test fun rejectedDispatchIsAFailureRatherThanAnAutomaticRetry() {
        val page = aboutPhone("1:06:39")
        var attempts = 0
        assertEquals(SetupActionResult.Failed, SetupAutomationAction.apply(page, page, true) { attempts++; false })
        assertEquals(1, attempts)
    }

    @Test fun evenAnUnchangedConfirmationCannotBeClicked() {
        val page = SetupAutomationTree(aboutPhone("1:06:39").nodes + SetupNode(6, 0, password = true))
        var dispatched = false
        assertEquals(SetupActionResult.Retry, SetupAutomationAction.apply(page, page, true) { dispatched = true; true })
        assertFalse(dispatched)
        assertEquals(SetupAutomationAction.RetryReason.Blocked, SetupAutomationAction.retryReason(page, page, true))
    }
}
