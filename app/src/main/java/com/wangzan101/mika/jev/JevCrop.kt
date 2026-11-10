package com.wangzan101.mika.jev

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** 解码器已按图片方向解释的归一化裁剪框；预览和推理必须共用同一图片快照及此框。 */
internal data class JevCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }

    /** [left] 等坐标相对于目标整图；右、下为不包含边界，与 ImageDecoder.setCrop 一致。 */
    data class Plan(val fullWidth: Int, val fullHeight: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * 按原图上的 ROI 决定缩放，避免先把整张长截图压到 1024 后才裁小字。
     * setCrop 仅保证输出裁剪，不能假定解码器只分配 ROI；整图目标仍限制到 4MP/4096。
     * 坐标向外取整保留边缘文字，因此局部框预留一个像素；不放大原图。
     */
    fun decodePlan(width: Int, height: Int, maxEdge: Int = 1024): Plan {
        require(width > 0 && height > 0 && maxEdge > 0)
        val w = width.toDouble()
        val h = height.toDouble()
        val roiWidth = w * (right.toDouble() - left.toDouble())
        val roiHeight = h * (bottom.toDouble() - top.toDouble())
        val roiBudget = when {
            maxEdge == 1 -> 1.0
            this == Full -> maxEdge.toDouble()
            else -> (maxEdge - 1).toDouble()
        }
        val scale = minOf(
            1.0,
            roiBudget / maxOf(roiWidth, roiHeight),
            MAX_DECODE_EDGE / maxOf(w, h),
            sqrt(MAX_DECODE_PIXELS / (w * h)),
            // 仅有一个输出像素时，向外取整不能横跨两列/行。
            if (maxEdge == 1) 1.0 / maxOf(w, h) else 1.0,
        )
        var fullWidth = floor(w * scale).toInt().coerceIn(1, width)
        var fullHeight = floor(h * scale).toInt().coerceIn(1, height)

        fun plan(): Plan {
            val x0 = floor(left.toDouble() * fullWidth).toInt().coerceIn(0, fullWidth - 1)
            val y0 = floor(top.toDouble() * fullHeight).toInt().coerceIn(0, fullHeight - 1)
            val x1 = ceil(right.toDouble() * fullWidth).toInt().coerceIn(x0 + 1, fullWidth)
            val y1 = ceil(bottom.toDouble() * fullHeight).toInt().coerceIn(y0 + 1, fullHeight)
            return Plan(fullWidth, fullHeight, x0, y0, x1, y1)
        }
        var result = plan()
        // 防浮点舍入恰落整数边界后多出一列；缩小目标整图，不截掉请求的 ROI 边缘。
        while (result.right - result.left > maxEdge || result.bottom - result.top > maxEdge) {
            val correction = (maxEdge - 1).coerceAtLeast(1).toDouble() /
                maxOf(result.right - result.left, result.bottom - result.top)
            fullWidth = floor(fullWidth * correction).toInt().coerceAtLeast(1)
            fullHeight = floor(fullHeight * correction).toInt().coerceAtLeast(1)
            result = plan()
        }
        return result
    }

    companion object {
        val Full = JevCrop(0f, 0f, 1f, 1f)
        private const val MAX_DECODE_EDGE = 4096.0
        private const val MAX_DECODE_PIXELS = 4.0 * 1024 * 1024
    }
}
