package com.wangzan101.mika.cap.phone.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 显式验收参数才调用设备上用户已配置的模型；只发送合成菜单，不读取/点击系统页面。 */
@RunWith(AndroidJUnit4::class)
class SetupModelConfiguredDeviceTest {
    @Test fun selectedMainModelCanChooseAnUnfamiliarSetupMenu() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifySetupModel") == "true")
        val store = ProviderStore.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val selected = store.modelFor(ModelRole.Main)
        assertNotNull("Configure and select the main model before running this explicit check", selected)
        val page = SetupAutomationTree(listOf(
            SetupNode(0), SetupNode(1, 0, clickable = true),
            SetupNode(2, 1, text = "关于此设备的软件详情", resource = "android:id/title"),
        ))
        assertEquals(null, page.click(SetupTarget.SoftwareInfo))
        val suggestion = SetupModelAdvisor({ selected }).suggest(SetupModelStage.Developer, page)
        assertNotNull("The configured model must return one valid setup action", suggestion)
        assertEquals(SetupModelAction.Navigate, suggestion!!.action)
        assertEquals(1, suggestion.nodeId)
    }
}
