package com.wangzan101.mika.cap.phone.display

import org.junit.Assert.*
import org.junit.Test

class PreviewDemandTest {
    @Test fun previewStaysRequestedUntilEveryVisibleConsumerCloses() {
        val demand = PreviewDemand()
        assertFalse(demand.active.value)
        val chat = demand.acquire()
        val capsule = demand.acquire()
        assertTrue(demand.active.value)
        chat.close()
        assertTrue(demand.active.value)
        chat.close() // 生命周期重复清理不能释放另一个消费者。
        assertTrue(demand.active.value)
        capsule.close()
        assertFalse(demand.active.value)
    }

    @Test fun closingAnOldLeaseCannotStopAReopenedPreview() {
        val demand = PreviewDemand()
        val old = demand.acquire()
        old.close()
        val reopened = demand.acquire()
        old.close()
        assertTrue(demand.active.value)
        reopened.close()
        assertFalse(demand.active.value)
    }
}
