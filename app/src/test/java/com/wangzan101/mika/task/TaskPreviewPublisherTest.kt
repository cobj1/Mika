package com.wangzan101.mika.task

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskPreviewPublisherTest {
    private class Harness {
        var target = TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Hidden)
        var shown: String? = null
        var phoneVisible = false
        var webReads = 0
        var phoneChecks = 0
        var allowed: suspend () -> Boolean = { true }
        var web: suspend () -> String? = { "web" }
        val publisher = TaskPreviewPublisher(
            target = { target }, phoneVisible = { phoneVisible = it },
            phoneAllowed = { phoneChecks++; allowed() }, phoneFrame = { "static-phone" },
            webFrame = { webReads++; web() }, publish = { shown = it },
        )
    }

    @Test fun hiddenPreviewsDoNotCopyWebFramesOrCheckPhoneWindows() = runTest {
        val h = Harness()
        repeat(5) { h.publisher.refresh(captureWeb = true) }
        assertEquals(0, h.webReads)
        assertEquals(0, h.phoneChecks)
        assertNull(h.shown)
        assertFalse(h.phoneVisible)
    }

    @Test fun switchingBackReplaysStaticPhoneWithoutWaitingForNewProducerFrame() = runTest {
        val h = Harness()
        h.target = TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Phone, "display")
        h.publisher.refresh()
        assertEquals("static-phone", h.shown)
        assertTrue(h.phoneVisible)
        h.target = TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Web, "tab")
        h.publisher.refresh(captureWeb = true)
        assertEquals("web", h.shown)
        assertFalse(h.phoneVisible)
        h.target = TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Phone, "display")
        h.publisher.refresh(captureWeb = true)
        assertEquals("static-phone", h.shown)
        assertTrue(h.phoneVisible)
        assertEquals(1, h.webReads)
    }

    @Test fun lateWebCopyCannotOverwritePhoneOrAnotherTab() = runTest {
        for (next in listOf(TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Phone, "display"),
            TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Web, "another-tab"))) {
            val h = Harness()
            val copy = CompletableDeferred<String>()
            h.web = { copy.await() }
            h.target = TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Web, "old-tab")
            val pending = launch { h.publisher.refresh(captureWeb = true) }
            runCurrent()
            h.target = next
            h.publisher.refresh()
            val before = h.shown
            copy.complete("old-web")
            pending.join()
            assertEquals(before, h.shown)
        }
    }

    @Test fun closingAndReopeningSamePreviewInvalidatesEarlierCopy() = runTest {
        val h = Harness()
        val copy = CompletableDeferred<String>()
        h.web = { copy.await() }
        val tab = TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Web, "tab")
        h.target = tab
        val pending = launch { h.publisher.refresh(captureWeb = true) }
        runCurrent()
        h.target = TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Hidden)
        h.publisher.refresh()
        h.target = tab
        h.publisher.refresh()
        copy.complete("old-web")
        pending.join()
        assertNull(h.shown)
        h.web = { "fresh-web" }
        h.publisher.refresh(captureWeb = true)
        assertEquals("fresh-web", h.shown)
    }

    @Test fun latePermissionApprovalCannotUndoNewerRevocation() = runTest {
        val h = Harness()
        val oldApproval = CompletableDeferred<Boolean>()
        h.target = TaskPreviewPublisher.Target(TaskPreviewPublisher.Mode.Phone, "display")
        h.allowed = { oldApproval.await() }
        val pending = launch { h.publisher.refresh() }
        runCurrent()
        h.allowed = { false }
        h.publisher.refresh()
        oldApproval.complete(true)
        pending.join()
        assertNull(h.shown)
        h.allowed = { true }
        h.publisher.refresh()
        assertEquals("static-phone", h.shown)
    }
}
