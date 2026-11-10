package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只检查授权入口和高级设置的显式启动，不修改测试设备的系统权限。 */
@RunWith(AndroidJUnit4::class)
class OnboardingPermissionScreensTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun accessibility(connected: Boolean) {
        compose.setContent {
            MikaTheme {
                AccessibilityOnboardingScreen(
                    connected = connected,
                    onOpen = { calls += "open" },
                    onAppInfo = { calls += "appInfo" },
                    onSkip = { calls += "skip" },
                    onContinue = { calls += "continue" },
                )
            }
        }
    }

    private fun advanced() {
        compose.setContent {
            MikaTheme {
                AdvancedFeaturesScreen(onEnable = { calls += "enable" }, onLater = { calls += "later" })
            }
        }
    }

    @Test fun ungrantedAccessibilityOnlyOpensSettingsOnClick() {
        accessibility(connected = false)
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("onboarding-accessibility-continue").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-accessibility-open").assertIsDisplayed().performClick()
        assertEquals(listOf("open"), calls)
    }

    @Test fun connectedAccessibilityContinuesToModelSetup() {
        accessibility(connected = true)
        compose.onNodeWithTag("onboarding-accessibility-open").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-accessibility-continue").assertIsDisplayed().performClick()
        assertEquals(listOf("continue"), calls)
    }

    @Test fun advancedSetupStartsOnlyAfterExplicitUnlock() {
        advanced()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("onboarding-advanced-enable").assertIsDisplayed().performClick()
        assertEquals(listOf("enable"), calls)
    }

    @Test fun deferringAdvancedSetupDoesNotEnableIt() {
        advanced()
        compose.onNodeWithTag("onboarding-advanced-later").assertIsDisplayed().performClick()
        assertEquals(listOf("later"), calls)
    }
}
