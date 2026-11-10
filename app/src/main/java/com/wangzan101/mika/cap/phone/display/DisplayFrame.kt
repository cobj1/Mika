package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * 系统把逻辑画面顺时针旋转到固定的物理缓冲;反向转回来,让预览、PNG 和 OCR 都用应用的逻辑坐标。
 * 直接画到目标尺寸,不为每张预览另建一张全尺寸的中间图;源矩形不含 ImageReader 的行填充。
 */
internal fun uprightDisplayFrame(
    buffer: Bitmap,
    geometry: DisplayFrameGeometry,
    maxWidth: Int = geometry.width,
    paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG),
): Bitmap {
    val (width, height) = geometry.previewSize(maxWidth)
    return drawDisplayRegion(buffer, geometry, DisplayRegionPlan(geometry.width, geometry.height,
        0, 0, geometry.width, geometry.height, width, height), paint)
}

/** 直接从物理缓冲画出旋转后的文字选区，只有最终有界区域分配 Bitmap。 */
internal fun drawDisplayRegion(buffer: Bitmap, geometry: DisplayFrameGeometry, region: DisplayRegionPlan,
    paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG),
): Bitmap {
    require(region.sourceWidth == geometry.width && region.sourceHeight == geometry.height)
    val width = region.width
    val height = region.height
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(result).apply {
            scale(width.toFloat() / (region.right - region.left), height.toFloat() / (region.bottom - region.top))
            translate(-region.left.toFloat(), -region.top.toFloat())
            when (geometry.rotation) {
                1 -> { translate(0f, geometry.bufferWidth.toFloat()); rotate(-90f) }
                2 -> { translate(geometry.bufferWidth.toFloat(), geometry.bufferHeight.toFloat()); rotate(180f) }
                3 -> { translate(geometry.bufferHeight.toFloat(), 0f); rotate(90f) }
            }
            val content = Rect(0, 0, geometry.bufferWidth, geometry.bufferHeight)
            drawBitmap(buffer, content, content, paint)
        }
        return result
    } catch (error: Throwable) { result.recycle(); throw error }
}
