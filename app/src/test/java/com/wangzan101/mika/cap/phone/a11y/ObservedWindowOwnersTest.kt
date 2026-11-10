package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservedWindowOwnersTest {
    @Test fun identityComesFromAnObservationOnTheSameDisplayAndWindow() {
        val owners = ObservedWindowOwners()
        assertNull(owners.packageOf(0, 42))
        owners.observe(0, 42, "com.android.settings")
        owners.observe(1, 42, "example.other")
        assertEquals("com.android.settings", owners.packageOf(0, 42))
        assertEquals("example.other", owners.packageOf(1, 42))
        assertNull(owners.packageOf(0, 43))
    }

    @Test fun removalAndReuseRequireANewObservation() {
        val owners = ObservedWindowOwners()
        owners.observe(0, 42, "com.android.settings")
        owners.observe(1, 42, "example.other")
        owners.remove(0, 42)
        assertNull(owners.packageOf(0, 42))
        assertEquals("example.other", owners.packageOf(1, 42))
        owners.observe(0, 42, "example.reused")
        assertEquals("example.reused", owners.packageOf(0, 42))
    }

    @Test fun removalWithoutDisplayInformationStillInvalidatesTheWindow() {
        val owners = ObservedWindowOwners()
        owners.observe(0, 42, "com.android.settings")
        owners.observe(1, 42, "example.other")
        owners.observe(0, 43, "example.remaining")
        owners.remove(-1, 42)
        assertNull(owners.packageOf(0, 42))
        assertNull(owners.packageOf(1, 42))
        assertEquals("example.remaining", owners.packageOf(0, 43))
        owners.remove(0, -1)
        assertNull(owners.packageOf(0, 43))
    }

    @Test fun missingPackageRevokesTheOldIdentityAndInvalidIdsCannotCreateOne() {
        val owners = ObservedWindowOwners()
        owners.observe(0, 42, "com.android.settings")
        owners.observe(0, 42, null)
        assertNull(owners.packageOf(0, 42))
        owners.observe(0, 42, "example.other")
        owners.observe(0, 42, "  ")
        owners.observe(-1, 42, "com.android.settings")
        owners.observe(0, -1, "com.android.settings")
        assertNull(owners.packageOf(0, 42))
        assertNull(owners.packageOf(-1, 42))
        assertNull(owners.packageOf(0, -1))
    }

    @Test fun reconnectCannotRetainAnyPreviousWindowIdentity() {
        val owners = ObservedWindowOwners()
        owners.observe(0, 42, "com.android.settings")
        owners.observe(1, 43, "example.other")
        owners.clear()
        assertNull(owners.packageOf(0, 42))
        assertNull(owners.packageOf(1, 43))
    }

    @Test fun missingRemovalEventsCannotAccumulateUnboundedWindowIdentities() {
        val owners = ObservedWindowOwners()
        repeat(130) { owners.observe(0, it, "example.app") }
        assertNull(owners.packageOf(0, 0))
        assertNull(owners.packageOf(0, 1))
        assertEquals("example.app", owners.packageOf(0, 129))
    }
}
