package com.wangzan101.mika.cap.phone.display

import com.wangzan101.mika.core.uistate.TextVerificationCandidates
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 逻辑原帧上的像素区域；OCR 坐标按实际向外取整的区域映回，不能按请求框估算。 */
internal data class DisplayRegionPlan(
    val sourceWidth: Int, val sourceHeight: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val width: Int, val height: Int,
) {
    fun screenBounds(bounds: List<Int>): List<Int> {
        require(TextVerificationCandidates.validRegion(bounds))
        fun edge(value: Int, low: Int, high: Int, size: Int, upper: Boolean): Int {
            val normalized = (low * 999.0 + value * (high - low)) / size
            return (if (upper) ceil(normalized) else floor(normalized)).toInt().coerceIn(0, 999)
        }
        return listOf(edge(bounds[0], left, right, sourceWidth, false), edge(bounds[1], top, bottom, sourceHeight, false),
            edge(bounds[2], left, right, sourceWidth, true), edge(bounds[3], top, bottom, sourceHeight, true))
    }

    companion object {
        fun of(width: Int, height: Int, region: List<Int>, maxEdge: Int = 1024): DisplayRegionPlan {
            require(width > 0 && height > 0 && maxEdge > 0)
            require(TextVerificationCandidates.validRegion(region))
            val left = floor(region[0] * width / 999.0).toInt().coerceIn(0, width - 1)
            val top = floor(region[1] * height / 999.0).toInt().coerceIn(0, height - 1)
            val right = ceil(region[2] * width / 999.0).toInt().coerceIn(left + 1, width)
            val bottom = ceil(region[3] * height / 999.0).toInt().coerceIn(top + 1, height)
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(right - left, bottom - top))
            return DisplayRegionPlan(width, height, left, top, right, bottom,
                ((right - left) * scale).roundToInt().coerceAtLeast(1), ((bottom - top) * scale).roundToInt().coerceAtLeast(1))
        }
    }
}
