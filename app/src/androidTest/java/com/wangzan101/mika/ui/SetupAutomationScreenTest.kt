package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.setup.SetupAutomationState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只测试用户发起/停止入口，不在测试设备上开启调试或修改系统权限。 */
@RunWith(AndroidJUnit4::class)
class SetupAutomationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun idleDoesNotStartUntilUserClicks() {
        var starts = 0
        compose.setContent { MikaTheme { SetupAutomationCard(SetupAutomationState(), { starts++ }, {}) } }
        compose.waitForIdle()
        assertEquals(0, starts)
        compose.onNodeWithTag("setup-automation-start").assertIsDisplayed().performClick()
        assertEquals(1, starts)
    }

    @Test fun activeSessionShowsProgressAndStopInsteadOfStart() {
        var stops = 0
        compose.setContent {
            MikaTheme {
                SetupAutomationCard(SetupAutomationState(true, R.string.setup_auto_usb), {}, { stops++ })
            }
        }
        compose.onNodeWithTag("setup-automation-status").assertIsDisplayed()
        compose.onNodeWithTag("setup-automation-start").assertDoesNotExist()
        compose.onNodeWithTag("setup-automation-stop").performClick()
        assertEquals(1, stops)
    }

    @Test fun onlineModelDisclosureIsVisibleBeforeEnablingAssistance() {
        var selected: Boolean? = null
        var starts = 0
        var disclosure = ""
        compose.setContent {
            MikaTheme {
                disclosure = stringResource(R.string.setup_auto_model_online)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SetupAutomationCard(
                        SetupAutomationState(), { starts++ }, {},
                        useModel = false, onUseModelChange = { selected = it }, modelAvailable = true,
                        modelName = "configured-main-model",
                    )
                }
            }
        }
        compose.onNodeWithTag("setup-automation-model-info").assertTextEquals(disclosure)
        compose.onNodeWithTag("setup-automation-model-name").assertTextEquals("configured-main-model")
        compose.onNodeWithTag("setup-automation-model").assertIsOff().performScrollTo().performClick()
        assertEquals(true, selected)
        assertEquals(0, starts)
    }

    @Test fun missingModelKeepsRulesAvailableAndDisablesAssistance() {
        var starts = 0
        var disclosure = ""
        compose.setContent {
            MikaTheme {
                disclosure = stringResource(R.string.setup_auto_model_unavailable)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SetupAutomationCard(
                        SetupAutomationState(), { starts++ }, {},
                        useModel = true, onUseModelChange = {}, modelAvailable = false,
                    )
                }
            }
        }
        compose.onNodeWithTag("setup-automation-model").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("setup-automation-model-info").assertTextEquals(disclosure)
        compose.onNodeWithTag("setup-automation-start").performScrollTo().performClick()
        assertEquals(1, starts)
    }

    @Test fun runningSessionKeepsModelChoiceLockedAndStopAvailable() {
        var stops = 0
        var disclosure = ""
        compose.setContent {
            MikaTheme {
                disclosure = stringResource(R.string.setup_auto_model_local)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SetupAutomationCard(
                        SetupAutomationState(true, R.string.setup_auto_model_thinking), {}, { stops++ },
                        useModel = true, onUseModelChange = {}, modelAvailable = true, modelIsLocal = true,
                    )
                }
            }
        }
        compose.onNodeWithTag("setup-automation-model").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("setup-automation-model-info").assertTextEquals(disclosure)
        compose.onNodeWithTag("setup-automation-stop").performScrollTo().performClick()
        assertEquals(1, stops)
    }
}
