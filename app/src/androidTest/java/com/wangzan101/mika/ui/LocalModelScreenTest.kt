package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.localmodel.LocalModelItem
import com.wangzan101.mika.localmodel.LocalModelPhase
import com.wangzan101.mika.localmodel.LocalModelState
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 只使用假状态，不联网、不下载权重、不启动推理。 */
@RunWith(AndroidJUnit4::class)
class LocalModelScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private fun model(ready: Boolean = false, allowed: Boolean = true) = LocalModelItem(
        id = "small", name = "Qwen3-0.6B", bytes = 600_000_000,
        ready = ready, downloadedBytes = if (ready) 600_000_000 else 0,
        downloadAllowed = allowed, reason = null, recommended = true,
    )
    private fun show(state: LocalModelState) = compose.setContent {
        MikaTheme { LocalModelContent(state, onBack = {}, onRefresh = { calls += "refresh" },
            onDownload = { calls += "download $it" }, onCancel = { calls += "cancel" },
            onRemove = { calls += "remove $it" }, onUse = { calls += "use $it" }) }
    }

    @Test fun modeChoiceWaitsForExplicitLocalOrOnlineChoice() {
        compose.setContent { MikaTheme { ModelModeChoice({ calls += "local" }, { calls += "online" }, {}) } }
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("model-mode-local").performScrollTo().performClick()
        compose.onNodeWithTag("model-mode-online").performScrollTo().performClick()
        assertEquals(listOf("local", "online"), calls)
    }

    @Test fun downloadedModelIsNotActivatedUntilUserChoosesIt() {
        show(LocalModelState(rows = listOf(model(ready = true)), supported = true))
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("local-download-small").assertDoesNotExist()
        compose.onNodeWithTag("local-use-small").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("use small"), calls)
    }

    @Test fun insufficientResourcesCannotStartDownloadButCanRecheck() {
        show(LocalModelState(rows = listOf(model(allowed = false)), supported = true))
        compose.onNodeWithTag("local-download-small").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("local-model-recheck").performScrollTo().performClick()
        assertEquals(listOf("refresh"), calls)
    }

    @Test fun activationCanBeStoppedWithoutStartingAnotherOperation() {
        show(LocalModelState(rows = listOf(model(ready = true)), phase = LocalModelPhase.Activating, activeId = "small", supported = true))
        compose.onNodeWithTag("local-use-small").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("local-model-stop").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test fun currentMainModelCannotBeDeleted() {
        show(LocalModelState(rows = listOf(model(ready = true)), enabledId = "small", supported = true))
        compose.onNodeWithTag("local-current-small").performScrollTo().assertExists()
        compose.onNodeWithTag("local-remove-small").performScrollTo().assertIsNotEnabled()
        assertTrue(calls.isEmpty())
    }

    @Test fun downloadedModelRemovalRequiresConfirmation() {
        show(LocalModelState(rows = listOf(model(ready = true)), supported = true))
        compose.onNodeWithTag("local-remove-small").performScrollTo().performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithTag("local-model-remove-confirm").performClick()
        assertEquals(listOf("remove small"), calls)
    }
}
