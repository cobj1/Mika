package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import com.wangzan101.mika.core.visual.FrameGrid
import com.wangzan101.mika.core.visual.FramePreview
import com.wangzan101.mika.core.visual.ObservedFrame
import java.io.ByteArrayOutputStream

/** 接管本次取帧的缩略图；筛选、编码、取消或异常的所有路径均立即回收，不保存位图闭包。 */
internal fun encodeObservationFrame(
    bitmap: Bitmap, timestampNanos: Long,
    shouldEncode: (FramePreview) -> Boolean,
    ensureActive: () -> Unit,
    encodePng: (Bitmap) -> ByteArray = { bmp ->
        ByteArrayOutputStream().also { check(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
    },
): ObservedFrame? = try {
    ensureActive()
    val grid = FrameGrid.sample(bitmap.width, bitmap.height, bitmap::getPixel)
    val preview = FramePreview(timestampNanos, bitmap.width, bitmap.height, grid)
    if (!shouldEncode(preview)) null else {
        ensureActive()
        val png = encodePng(bitmap)
        ensureActive()
        ObservedFrame(timestampNanos, bitmap.width, bitmap.height, grid, png)
    }
} finally { bitmap.recycle() }
