package com.wangzan101.mika.jev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 固定版本 llama.cpp 的 Jev 分类接口；不是聊天补全，也不把异常分数当成“不一致”。 */
internal object JevProtocol {
    const val MAX_TEXT = 1_000
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    fun listeningPort(line: String): Int? = Regex("listening on http://127\\.0\\.0\\.1:(\\d+)(?:\\s|$)")
        .find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun request(text: String, originalPng: String, renderedPng: String): String {
        require(text.isNotBlank() && text.length <= MAX_TEXT)
        require(originalPng.isNotEmpty() && renderedPng.isNotEmpty())
        return buildJsonObject {
            put("state", text)
            putJsonArray("images") {
                add(JsonPrimitive("data:image/png;base64,$originalPng"))
                add(JsonPrimitive("data:image/png;base64,$renderedPng"))
            }
            putJsonObject("questions") { putJsonObject("ocr") { put("type", "noul") } }
        }.toString()
    }

    fun score(body: String): Double {
        val root = Json.parseToJsonElement(body).jsonObject
        require(root["error"] == null || root["error"] == JsonNull)
        val score = root["answers"]?.jsonObject?.get("ocr")?.jsonObject?.get("noul")
            ?.jsonPrimitive?.doubleOrNull ?: error("missing Jev score")
        require(score.isFinite() && score in 0.0..1.0)
        return score
    }
}
