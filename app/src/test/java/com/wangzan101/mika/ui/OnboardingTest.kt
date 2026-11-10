package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingTest {
    private fun inputs(hasMain: Boolean = false, modelDone: Boolean = false, phoneNeeded: Boolean = true, phoneSeen: Boolean = false) =
        Onboarding.Inputs(hasMain, modelDone, phoneNeeded, phoneSeen)

    @Test fun freshInstallRequestsAccessibilityThenModelThenInvitesAdvanced() {
        val i = inputs()
        assertEquals(OnboardingStep.Accessibility, Onboarding.start(i))
        assertEquals(OnboardingStep.Model, Onboarding.next(OnboardingStep.Accessibility, i))
        assertEquals(OnboardingStep.Advanced, Onboarding.next(OnboardingStep.Model, i.copy(hasMainModel = true)))
        // 只有用户点了解锁才进入 Phone；启动和保存模型只显示邀请。
        assertEquals(OnboardingStep.Phone, Onboarding.next(OnboardingStep.Advanced, i))
        assertNull(Onboarding.next(OnboardingStep.Phone, i))
    }

    @Test fun connectedOrSkippedAccessibilityResumesAtModel() {
        assertEquals(OnboardingStep.Model, Onboarding.start(inputs().copy(accessibilityConnected = true)))
        assertEquals(OnboardingStep.Model, Onboarding.start(inputs().copy(accessibilityGuideSeen = true)))
    }

    @Test fun configuredUsersAreInvitedWithoutRepeatingPermissionSetup() {
        assertEquals(OnboardingStep.Advanced, Onboarding.start(inputs(hasMain = true)))
        assertNull(Onboarding.start(inputs(hasMain = true, phoneSeen = true)))
        assertNull(Onboarding.start(inputs(hasMain = true, phoneNeeded = false)))
    }

    @Test fun modelSetupDoneOnceIsNotRepeatedEvenIfModelsWereRemoved() {
        assertEquals(OnboardingStep.Advanced, Onboarding.start(inputs(modelDone = true)))
        assertNull(Onboarding.start(inputs(modelDone = true, phoneSeen = true)))
    }

    @Test fun decliningAdvancedIsRememberedWithoutPretendingPhoneWasConfigured() {
        val i = inputs(hasMain = true).copy(advancedPromptSeen = true)
        assertNull(Onboarding.start(i))
        assertNull(Onboarding.next(OnboardingStep.Model, i))
        assertEquals(false, i.phoneGuideSeen)
    }

    @Test fun skipsAdvancedWhenReadyOrPreviouslyConfigured() {
        assertNull(Onboarding.next(OnboardingStep.Model, inputs(phoneNeeded = false)))
        assertNull(Onboarding.next(OnboardingStep.Model, inputs(phoneSeen = true)))
        assertNull(Onboarding.next(OnboardingStep.Advanced, inputs(phoneNeeded = false)))
    }

    @Test fun dotsRepresentThreeUserFacingStages() {
        assertEquals(0, Onboarding.dot(OnboardingStep.Accessibility))
        assertEquals(1, Onboarding.dot(OnboardingStep.Model))
        assertEquals(2, Onboarding.dot(OnboardingStep.Advanced))
        assertEquals(2, Onboarding.dot(OnboardingStep.Phone))
        assertEquals(3, Onboarding.DOTS)
    }
}
