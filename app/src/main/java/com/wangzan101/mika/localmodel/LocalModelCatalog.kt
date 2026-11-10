package com.wangzan101.mika.localmodel

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.jev.JevModelArtifact
import kotlinx.coroutines.sync.Mutex
import okhttp3.HttpUrl.Companion.toHttpUrl

data class LocalModelSpec(val id: String, val name: String, val contextTokens: Int,
    val minAvailableBytes: Long, val artifact: JevModelArtifact, val revision: String) {
    val capabilities get() = Capabilities(true, false, contextTokens, false, promptCaching = PromptCaching.None, maxImages = 0)
    fun listed() = ListedModel(id, toolCalls = true, vision = false, contextTokens = contextTokens, reasoning = false, displayName = name)
}

/** 仅下载官方固定版本的 GGUF 数据；版本与 SHA 来自官方 LFS 元数据。 */
object LocalModelCatalog {
    const val BASE_URL = "http://127.0.0.1/mika-on-device/v1"
    const val PROVIDER_NAME = "Mika · On-device"
    const val STORAGE_RESERVE = 128L * 1024 * 1024
    private const val GIB = 1024L * 1024 * 1024
    val models = listOf(
        spec("qwen3-0.6b-q8", "Qwen3 0.6B · Q8_0", "Qwen3-0.6B", "23749fefcc72300e3a2ad315e1317431b06b590a",
            639_446_688L, "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", 3 * GIB),
        spec("qwen3-1.7b-q8", "Qwen3 1.7B · Q8_0", "Qwen3-1.7B", "90862c4b9d2787eaed51d12237eafdfe7c5f6077",
            1_834_426_016L, "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a", 4 * GIB),
    )
    fun find(id: String): LocalModelSpec? = models.firstOrNull { it.id == id }
    fun recommended(available: Long, freeStorage: Long, remaining: (LocalModelSpec) -> Long): String? =
        models.lastOrNull { available >= it.minAvailableBytes && freeStorage >= remaining(it).coerceAtLeast(0) + STORAGE_RESERVE }?.id

    private fun spec(id: String, name: String, model: String, revision: String, bytes: Long, sha: String, memory: Long) =
        LocalModelSpec(id, name, 16_384, memory,
            JevModelArtifact("$model-Q8_0.gguf", bytes, sha,
                "https://huggingface.co/Qwen/$model-GGUF/resolve/$revision/$model-Q8_0.gguf".toHttpUrl()), revision)
}

/** 主模型、Jev 推理和本地主模型文件变更互斥，避免同时驻留或运行中删除权重。 */
object LocalModelAccess { val mutex = Mutex() }
