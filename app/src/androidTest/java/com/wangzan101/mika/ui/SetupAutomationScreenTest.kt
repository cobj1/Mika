package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
}
