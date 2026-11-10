package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.jev.JevPhase
import com.wangzan101.mika.jev.JevState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JevScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private fun show(state: JevState, hasImage: Boolean = false, text: String = "") = compose.setContent {
        MikaTheme { JevContent(state, null, hasImage, text, {}, {}, { calls += "score" }, { calls += "download" }, { calls += "cancel" }, { calls += "remove" }, {}) }
    }

    @Test fun unsupportedDeviceCannotStartDownload() {
        show(JevState(supported = false))
        compose.onNodeWithTag("jev-download").performScrollTo().assertIsNotEnabled()
    }

    @Test fun readyModelRequiresBothImageAndCandidate() {
        show(JevState(ready = true), text = "文字")
        compose.onNodeWithTag("jev-score").performScrollTo().assertIsNotEnabled()
    }

    @Test fun stoppingIsAvailableDuringInference() {
        show(JevState(phase = JevPhase.Scoring, ready = true), hasImage = true, text = "文字")
        compose.onNodeWithTag("jev-stop").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test fun deletingRequiresConfirmation() {
        show(JevState(ready = true))
        compose.onNodeWithTag("jev-remove").performScrollTo().performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("jev-remove-confirm").performClick()
        assertEquals(listOf("remove"), calls)
    }

    @Test fun cachedScoreShowsCacheLabelWithoutReusingOldTiming() {
        show(JevState(ready = true, score = 0.87, cacheHit = true, loadMs = 900, scoreMs = 1200), hasImage = true, text = "文字")
        compose.onNodeWithTag("jev-score-value").performScrollTo().assertExists()
        compose.onNodeWithTag("jev-cached").performScrollTo().assertExists()
        compose.onNodeWithTag("jev-timing").assertDoesNotExist()
    }

    @Test fun freshScoreShowsLoadAndInferenceTiming() {
        show(JevState(ready = true, score = 0.87, cacheHit = false, loadMs = 900, scoreMs = 1200), hasImage = true, text = "文字")
        compose.onNodeWithTag("jev-timing").performScrollTo().assertExists()
        compose.onNodeWithTag("jev-cached").assertDoesNotExist()
    }

    @Test fun invalidOrMissingScoreHidesCacheAndTimingLabels() {
        show(JevState(ready = true, score = Double.NaN, cacheHit = true, loadMs = 900, scoreMs = 1200), hasImage = true, text = "文字")
        compose.onNodeWithTag("jev-score-value").assertDoesNotExist()
        compose.onNodeWithTag("jev-cached").assertDoesNotExist()
        compose.onNodeWithTag("jev-timing").assertDoesNotExist()
    }
}
