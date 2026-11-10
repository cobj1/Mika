package com.wangzan101.mika.cap.phone.display

import com.wangzan101.mika.core.visual.FrameGrid

/** 仅在 frameLock 下使用。按需直接采样原缓冲，不为内部判断创建 UI 缩略图。 */
internal class DisplayFrameAnalysis(geometry: DisplayFrameGeometry, pixel: (Int, Int) -> Int) {
    private val logicalPixel: (Int, Int) -> Int = { x, y ->
        when (geometry.rotation) {
            1 -> pixel(geometry.bufferWidth - 1 - y, x)
            2 -> pixel(geometry.bufferWidth - 1 - x, geometry.bufferHeight - 1 - y)
            3 -> pixel(y, geometry.bufferHeight - 1 - x)
            else -> pixel(x, y)
        }
    }
    val signature: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        FrameMath.lumaGrid(geometry.width, geometry.height, logicalPixel)
    }
    val black: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        FrameMath.isBlack(geometry.width, geometry.height, logicalPixel)
    }
    val grid: FrameGrid? by lazy(LazyThreadSafetyMode.NONE) {
        FrameGrid.sample(geometry.width, geometry.height, logicalPixel)
    }
}
