package com.wangzan101.mika.jev

import android.graphics.ImageDecoder
import android.graphics.Rect
import com.wangzan101.mika.cap.phone.a11y.PhoneOcr
import com.wangzan101.mika.core.uistate.VisibleTextLine
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** 选中区域的原图快照只交给本机 OCR；候选由用户选择和修改。 */
internal object JevImageOcr {
    /** bounds 相对于实际解码区域，必须带着向外取整后的区域一起映回原图。 */
    data class Result(val lines: List<VisibleTextLine>, val decodedCrop: JevCrop) {
        fun cropFor(bounds: List<Int>): JevCrop? {
            if (bounds.size != 4 || bounds.any { it !in 0..999 } || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) return null
            val width = decodedCrop.right - decodedCrop.left
            val height = decodedCrop.bottom - decodedCrop.top
            return JevCrop(
                decodedCrop.left + width * ((bounds[0] - 3).coerceAtLeast(0) / 999f),
                decodedCrop.top + height * ((bounds[1] - 3).coerceAtLeast(0) / 999f),
                (decodedCrop.left + width * ((bounds[2] + 3).coerceAtMost(999) / 999f)).coerceAtMost(1f),
                (decodedCrop.top + height * ((bounds[3] + 3).coerceAtMost(999) / 999f)).coerceAtMost(1f),
            )
        }
    }

    internal fun decodedCrop(plan: JevCrop.Plan) = JevCrop(plan.left.toFloat() / plan.fullWidth, plan.top.toFloat() / plan.fullHeight,
        plan.right.toFloat() / plan.fullWidth, plan.bottom.toFloat() / plan.fullHeight)

    suspend fun read(source: JevImages.Source, crop: JevCrop): Result {
        coroutineContext.ensureActive()
        var actualCrop: JevCrop? = null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source.decodingBuffer())) { decoder, info, _ ->
            val plan = crop.decodePlan(info.size.width, info.size.height, maxEdge = 2048)
            actualCrop = decodedCrop(plan)
            decoder.setTargetSize(plan.fullWidth, plan.fullHeight)
            decoder.setCrop(Rect(plan.left, plan.top, plan.right, plan.bottom))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try { coroutineContext.ensureActive() }
        catch (error: Throwable) { bitmap.recycle(); throw error }
        // PhoneOcr 在两套识别器都完成后回收，取消时不能提前回收仍被使用的图片。
        val lines = PhoneOcr.read(bitmap).filter { it.text.isNotBlank() && it.bounds.size == 4 }
            .distinctBy { it.text to it.bounds }
            .sortedWith(compareBy({ it.bounds[1] }, { it.bounds[0] })).take(24)
        return Result(lines, requireNotNull(actualCrop))
    }
}
