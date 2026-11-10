package com.wangzan101.mika.core.uistate

import kotlin.math.ceil
import kotlin.math.floor

/** 校验候选绑定到同一帧的指定区域；bounds 仍是全屏归一化 0–999 坐标。 */
data class TextVerificationCandidate(
    val text: String,
    val bounds: List<Int>,
    val source: String,
    val ocrConfidence: Float? = null,
)

/**
 * Jev 仅给文字与图片的一致性分数，不能据此声称文字正确、控件可点击或业务完成。
 * capturedAgoMs 从本次复制截图起计时，不冒充生产者帧的绝对年龄。
 */
data class TextVerification(
    val region: List<Int>,
    val candidate: TextVerificationCandidate? = null,
    val candidates: List<TextVerificationCandidate> = emptyList(),
    val score: Double? = null,
    val producerTimestampNanos: Long? = null,
    val capturedAgoMs: Long? = null,
    val newerFrameAvailable: Boolean? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val cacheHit: Boolean? = null,
    val loadMs: Long? = null,
    val scoreMs: Long? = null,
    val error: String? = null,
    val message: String? = null,
)

/** 小区域中仍出现多个 OCR 版本时交还候选，不擅自拼字或按低置信度选出“正确”答案。 */
object TextVerificationCandidates {
    const val MAX_TEXT = 1_000
    const val MAX_CANDIDATES = 8

    fun validRegion(region: List<Int>): Boolean = region.size == 4 && region.all { it in 0..999 } &&
        region[0] < region[2] && region[1] < region[3]

    fun fromOcr(lines: List<VisibleTextLine>, region: List<Int>): List<TextVerificationCandidate> {
        require(validRegion(region))
        return lines.filter { it.text.isNotBlank() && it.text.length <= MAX_TEXT && validRegion(it.bounds) }
            .sortedWith(compareBy({ it.bounds[1] }, { it.bounds[0] }))
            .map { line -> TextVerificationCandidate(
                text = line.text.trim(),
                bounds = listOf(
                    map(line.bounds[0], region[0], region[2], false), map(line.bounds[1], region[1], region[3], false),
                    map(line.bounds[2], region[0], region[2], true), map(line.bounds[3], region[1], region[3], true),
                ),
                source = "same_frame_ocr",
                ocrConfidence = line.confidence?.takeIf { it.isFinite() && it in 0f..1f },
            ) }
            // 两套识别器完全相同的输出只保留一次；位置不同的相同文字仍是两条候选。
            .distinctBy { it.text to it.bounds }
    }

    /** 同一 ROI 有两个略有偏差的识别框但文字完全相同时，允许作为同一个候选。 */
    fun unique(candidates: List<TextVerificationCandidate>): TextVerificationCandidate? {
        if (candidates.isEmpty() || candidates.map { it.text }.distinct().size != 1) return null
        val first = candidates.first()
        if (candidates.drop(1).any { overlap(first.bounds, it.bounds) < 0.65 }) return null
        return first
    }

    private fun map(value: Int, low: Int, high: Int, upper: Boolean): Int {
        val edge = low + value * (high - low) / 999.0
        return (if (upper) ceil(edge) else floor(edge)).toInt()
    }

    private fun overlap(a: List<Int>, b: List<Int>): Double {
        val area = minOf((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1]))
        val intersection = (minOf(a[2], b[2]) - maxOf(a[0], b[0])).coerceAtLeast(0) *
            (minOf(a[3], b[3]) - maxOf(a[1], b[1])).coerceAtLeast(0)
        return if (area > 0) intersection.toDouble() / area else 0.0
    }
}
