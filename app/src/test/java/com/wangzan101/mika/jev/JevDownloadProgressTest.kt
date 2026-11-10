package com.wangzan101.mika.jev

import org.junit.Assert.assertEquals
import org.junit.Test

class JevDownloadProgressTest {
    @Test fun throttlesSmallReadsButAlwaysPublishesVerifiedCompletion() {
        var now = 0L
        val updates = mutableListOf<Long>()
        val progress = JevDownloadProgress(1_000, { done, total ->
            assertEquals(1_000L, total)
            updates += done
        }, { now })
        progress.update(0)
        for (n in 1..999) {
            now = n.toLong()
            progress.update(n.toLong())
        }
        progress.update(1_000, force = true)
        assertEquals(listOf(0L, 200L, 400L, 600L, 800L, 1_000L), updates)
    }

    @Test fun rangeFallbackReportsBackwardProgressImmediately() {
        val updates = mutableListOf<Long>()
        val progress = JevDownloadProgress(1_000, { done, _ -> updates += done }, { 0L })
        progress.update(700)
        progress.update(0)
        progress.update(10)
        progress.update(1_000, force = true)
        assertEquals(listOf(700L, 0L, 1_000L), updates)
    }
}
