package com.wangzan101.mika.jev

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext

/** 与图片损坏分开报告，避免合法多行文字超出渲染高度后误导用户重新选图。 */
internal class JevTextLayoutException : IllegalArgumentException("Jev text exceeds layout limits")

/** 用户明确选择的文字裁剪图，限制解码尺寸；图片与文字不写入日志或共享目录。 */
internal object JevImages {
    const val PREPROCESSING_VERSION = "android-text-roi-v2"
    data class Pair(val original: String, val rendered: String)
    /** 同一次选择的预览与推理共用只读快照，避免内容 URI 被替换后裁错区域。 */
    class Source internal constructor(encoded: ByteArray) {
        // 自有副本且只暴露只读视图；摘要和后续解码永远对应同一份内容。
        init { require(encoded.isNotEmpty() && encoded.size <= JevProtocol.MAX_IMAGE_BYTES) }
        private val encoded = encoded.copyOf()
        val contentDigest: String = MessageDigest.getInstance("SHA-256").digest(this.encoded)
            .joinToString("") { "%02x".format(it) }

        internal fun decodingBuffer(): ByteBuffer = ByteBuffer.wrap(encoded).asReadOnlyBuffer()
    }

    suspend fun read(context: Context, uri: Uri): Source {
        coroutineContext.ensureActive()
        require(uri.scheme == "content")
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= JevProtocol.MAX_IMAGE_BYTES)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: error("image unavailable")
        coroutineContext.ensureActive()
        require(bytes.isNotEmpty())
        return Source(bytes)
    }

    suspend fun preview(source: Source): Bitmap {
        coroutineContext.ensureActive()
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source.decodingBuffer())) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width > 0 && height > 0)
            val scale = minOf(1.0, 640.0 / maxOf(width, height))
            decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try { coroutineContext.ensureActive(); return bitmap }
        catch (error: Throwable) { bitmap.recycle(); throw error }
    }

    suspend fun prepare(source: Source, text: String, crop: JevCrop = JevCrop.Full): Pair {
        coroutineContext.ensureActive()
        // 文字先验证，超高/超长输入不再触发图片解码或错误归因。
        val layout = textLayout(text)
        coroutineContext.ensureActive()
        val original = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source.decodingBuffer())) { decoder, info, _ ->
            // 先由原图上的文字区域计算缩放，保留小区域细节；同时限制整图的解码目标。
            // ImageDecoder 的裁剪只保证输出区域，不将其当作底层区域解码或峰值内存保证。
            val plan = crop.decodePlan(info.size.width, info.size.height)
            decoder.setTargetSize(plan.fullWidth, plan.fullHeight)
            decoder.setCrop(Rect(plan.left, plan.top, plan.right, plan.bottom))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            coroutineContext.ensureActive()
            val rendered = render(layout)
            try {
                coroutineContext.ensureActive()
                val originalPng = png(original)
                coroutineContext.ensureActive()
                val renderedPng = png(rendered)
                coroutineContext.ensureActive()
                return Pair(originalPng, renderedPng)
            }
            finally { rendered.recycle() }
        } finally { original.recycle() }
    }

    /** Android 本机字体渲染，仅适用于普通文字；与官方 Chromium 字体栈不同，分数需在目标数据上校准。 */
    private fun textLayout(text: String): StaticLayout {
        if (text.isBlank() || text.length > JevProtocol.MAX_TEXT) throw JevTextLayoutException()
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 720)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true)
            .setLineSpacing(4f, 1f).build()
        if (layout.height > 2048) throw JevTextLayoutException()
        return layout
    }

    private fun render(layout: StaticLayout): Bitmap {
        val bitmap = Bitmap.createBitmap(768, (layout.height + 48).coerceAtLeast(80), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.translate(24f, 24f)
            layout.draw(canvas)
            canvas.restore()
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun png(bitmap: Bitmap): String = ByteArrayOutputStream().use { bytes ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
        Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }
}
