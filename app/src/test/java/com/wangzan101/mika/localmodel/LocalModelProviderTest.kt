package com.wangzan101.mika.localmodel

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class LocalModelProviderTest {
    @Test fun failedActivationWriteKeepsPreviousRolesAndProviders() {
        val original = store()
        val remote = original.connect("Online", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "key",
            listOf(ListedModel("main")))
        assertTrue(original.use(ModelRole.Main, remote.id, "main", spec.capabilities))
        val roles = original.roles.value
        val providers = original.providers.value
        val configs = original.list()
        // 真实写入失败：让临时文件路径被目录占用，主配置文件保持原状。
        assertTrue(java.io.File(tmp.root, "providers.json.tmp").mkdir())
        val failure = runCatching { LocalModelActivation.select(original, spec, listOf(spec.listed())) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(roles, original.roles.value)
        assertEquals(providers, original.providers.value)
        assertEquals(configs, original.list())
        assertEquals(roles, store().roles.value)
    }
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val spec = LocalModelCatalog.models.first()
    private val built = mutableListOf<String>()
    private val completed = mutableListOf<String>()

    private fun store(withLocalEngine: Boolean = true) = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        modelLister = { _, _, _, _ -> throw AssertionError("本地模型不能访问在线模型列表") },
        localModelFactory = if (withLocalEngine) ({ id: String ->
            built += id
            object : ChatModel {
                override val capabilities = LocalModelCatalog.find(id)!!.capabilities
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    completed += id
                    return ModelReply(listOf(Part.Text(id)), StopReason.EndTurn, Usage(1, 1))
                }
            }
        }) else null,
        modelFactory = { _, _, _, _ -> throw AssertionError("本地模型不能构建 HTTP 模型") },
    )

    private fun selectMain(store: ProviderStore, capabilities: Capabilities = spec.capabilities): String {
        val provider = store.connect(LocalModelCatalog.PROVIDER_NAME, ProviderConfig.Kind.OpenAiCompat,
            LocalModelCatalog.BASE_URL, null, listOf(spec.listed()), sessionHeader = null, verified = setOf(spec.id))
        assertTrue(store.use(ModelRole.Main, provider.id, spec.id, capabilities))
        return provider.id
    }

    @Test fun savedRolesReloadThroughLocalFactoryAndListWithoutAnyHttpAdapter() = runTest {
        val original = store()
        val providerId = selectMain(original)
        assertTrue(original.choose(ModelRole.Light, RoleChoice.SameAsMain))
        assertTrue(original.choose(ModelRole.Vision, RoleChoice.Off))
        val expectedRoles = original.roles.value

        val reloaded = store()
        assertEquals(expectedRoles, reloaded.roles.value)
        assertEquals(LocalModelCatalog.BASE_URL, reloaded.configFor(ModelRole.Main)!!.baseUrl)
        assertEquals(ModelListResult.Ok(listOf(spec.listed())), reloaded.listModels(reloaded.provider(providerId)!!))
        assertEquals(ModelListResult.Ok(listOf(spec.listed())), reloaded.listModels(
            ProviderConfig.Kind.OpenAiCompat, LocalModelCatalog.BASE_URL, null))
        for (role in listOf(ModelRole.Main, ModelRole.Light)) {
            val reply = reloaded.modelFor(role)!!.complete("s", emptyList(), emptyList())
            assertEquals(listOf(Part.Text(spec.id)), reply.parts)
        }
        assertNull(reloaded.modelFor(ModelRole.Vision))
        assertEquals(listOf(spec.id, spec.id), built)
        assertEquals(listOf(spec.id, spec.id), completed)
    }

    @Test fun absentLocalEngineFailsLocallyInsteadOfFallingBackToHttp() {
        selectMain(store())
        val reloaded = store(withLocalEngine = false)
        val failure = runCatching { reloaded.modelFor(ModelRole.Main) }.exceptionOrNull()
        assertTrue(failure is ModelError.LocalUnavailable)
        assertTrue(built.isEmpty())
    }

    @Test fun selectingVisionAndOverridingCapabilitiesCannotMakeTextModelSeeImages() {
        val original = store()
        val providerId = selectMain(original, spec.capabilities.copy(vision = true, groundingVerified = true, maxImages = 100))
        val configId = original.configFor(ModelRole.Main)!!.id
        assertTrue(original.setModelVision(providerId, spec.id, true))
        for (choice in listOf(RoleChoice.SameAsMain, RoleChoice.Model(configId))) {
            assertTrue(original.choose(ModelRole.Vision, choice))
            original.setVisionGroundingVerified(true)
            for (checked in listOf(original, store())) {
                assertNull(checked.configFor(ModelRole.Vision))
                assertNull(checked.modelFor(ModelRole.Vision))
                for (role in listOf(ModelRole.Main, ModelRole.Light)) {
                    val capabilities = checked.configFor(role)!!.capabilities
                    assertFalse(capabilities.vision)
                    assertFalse(capabilities.groundingVerified)
                    assertEquals(0, capabilities.maxImages)
                    assertFalse(checked.modelFor(role)!!.capabilities.vision)
                }
            }
        }
    }

    @Test fun persistedContextOverrideCannotExceedActualLocalRuntimeWindow() {
        val original = store()
        val providerId = selectMain(original, spec.capabilities.copy(contextTokens = 1_000_000))
        val contextOverride = ModelContextSettings(131_072, 65_536)
        assertTrue(original.setModelContext(original.configFor(ModelRole.Main)!!.id, contextOverride))
        val reloaded = store()
        assertEquals(contextOverride, reloaded.provider(providerId)!!.contextSettings[spec.id])
        for (checked in listOf(original, reloaded)) {
            for (role in listOf(ModelRole.Main, ModelRole.Light)) {
                assertEquals(16_384, checked.configFor(role)!!.capabilities.contextTokens)
                val runtime = checked.modelFor(role)!!.capabilities
                assertEquals(16_384, runtime.contextTokens)
                assertNull(runtime.compactionTriggerTokens)
            }
        }
    }

    @Test fun controllerActivationReplacesPreviouslyOnlineAuxiliaryChoicesAndPersistsThem() = runTest {
        val original = store()
        val remote = original.connect("Online", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "key",
            listOf(ListedModel("main"), ListedModel("vision"), ListedModel("light")))
        assertTrue(original.use(ModelRole.Main, remote.id, "main", spec.capabilities))
        assertTrue(original.use(ModelRole.Vision, remote.id, "vision", spec.capabilities.copy(vision = true)))
        assertTrue(original.use(ModelRole.Light, remote.id, "light", spec.capabilities))

        // 调用控制器在校验成功后使用的同一提交入口，覆盖已有在线用途而非仅依赖默认值。
        LocalModelActivation.select(original, spec, listOf(spec.listed()))
        for (checked in listOf(original, store())) {
            assertEquals(spec.id, checked.configFor(ModelRole.Main)!!.model)
            assertEquals(LocalModelCatalog.BASE_URL, checked.configFor(ModelRole.Main)!!.baseUrl)
            assertEquals(RoleChoice.Off, checked.roles.value.vision)
            assertEquals(RoleChoice.SameAsMain, checked.roles.value.light)
            assertNull(checked.modelFor(ModelRole.Vision))
            checked.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
            checked.modelFor(ModelRole.Light)!!.complete("s", emptyList(), emptyList())
        }
        assertEquals(List(4) { spec.id }, completed)
    }
}
