package com.wangzan101.mika.cap.phone.setup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupAutomationAvailabilityTest {
    @Test fun protectedWindowReturnsToManualStepsInsteadOfWaitingForTheStepTimeout() {
        val availability = SetupAutomationAvailability()
        assertFalse(availability.unreadable(188, 10_000))
        assertFalse(availability.unreadable(188, 13_999))
        assertTrue(availability.unreadable(188, 14_000))
    }

    @Test fun readablePageOrNewWindowRestartsTheGracePeriod() {
        val availability = SetupAutomationAvailability()
        assertFalse(availability.unreadable(188, 10_000))
        availability.reset()
        assertFalse(availability.unreadable(188, 20_000))
        assertFalse(availability.unreadable(189, 24_000))
        assertFalse(availability.unreadable(189, 27_999))
        assertTrue(availability.unreadable(189, 28_000))
    }

    @Test fun noActiveApplicationDoesNotBecomeAProtectedSettingsFailure() {
        val availability = SetupAutomationAvailability()
        assertFalse(availability.unreadable(188, 0))
        assertFalse(availability.unreadable(null, 9_000))
        assertFalse(availability.unreadable(188, 10_000))
        assertFalse(availability.unreadable(null, 99_000))
    }
}
