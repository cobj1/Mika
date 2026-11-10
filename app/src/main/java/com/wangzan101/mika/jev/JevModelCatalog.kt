package com.wangzan101.mika.jev

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 固定版本的权重元数据。来自 Hugging Face 官方仓库的 LFS SHA-256，不跟随 main 漂移。 */
object JevModelCatalog {
    const val revision = "6bd2b4b932bbab92de97cea4cc6a754db96fc848"
    const val repository = "openbmb/MiniCPM-OCR-Jev-gguf"
    val files: List<JevModelArtifact> = listOf(
        artifact("MiniCPM-OCR-Jev-Q4_K_M.gguf", 1_274_405_248L, "f4921483b445b32c25c4c77ddca9f86e707b0f505ecc3dfef31e041dff6f4edb"),
        artifact("mmproj-model-f16.gguf", 671_372_416L, "cad9364b900f79a90c12e371aa07a7cb901966c9f673262379c31b051eeb313a"),
    )
    val totalBytes: Long = files.sumOf { it.bytes }

    private fun artifact(name: String, bytes: Long, sha256: String) = JevModelArtifact(
        name, bytes, sha256, "https://huggingface.co/$repository/resolve/$revision/$name".toHttpUrl(),
    )

    /** HF 会重定向到 LFS/Xet CDN；始终要求 HTTPS，避免下载被转到明文或任意主机。 */
    internal fun allows(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() &&
        (url.host == "huggingface.co" || url.host.endsWith(".huggingface.co") || url.host.endsWith(".hf.co"))
}

data class JevModelArtifact(val name: String, val bytes: Long, val sha256: String, val url: HttpUrl) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
        require(bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
    }
}
