package com.wangzan101.mika.jev

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** 内存图片走真实 Android ImageDecoder；不需要下载模型或启动原生推理。 */
@RunWith(AndroidJUnit4::class)
class JevImagesTest {
    private fun source(width: Int, height: Int, paint: (Bitmap) -> Unit = {}): JevImages.Source {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            paint(bitmap)
            JevImages.Source(ByteArrayOutputStream().also {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray())
        } finally { bitmap.recycle() }
    }

    private fun decode(base64: String): Bitmap {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("invalid output PNG")
    }

    @Test fun normalizedCropUsesOriginalPixelsAndExcludesRightBottomEdges() = runBlocking {
        val source = source(128, 96) { bitmap ->
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) bitmap.setPixel(x, y, Color.rgb(x, y, 31))
        }
        val prepared = JevImages.prepare(source, "sample", JevCrop(0.25f, 0.25f, 0.75f, 0.75f))
        val crop = decode(prepared.original)
        try {
            assertEquals(64, crop.width)
            assertEquals(48, crop.height)
            assertEquals(Color.rgb(32, 24, 31), crop.getPixel(0, 0))
            assertEquals(Color.rgb(95, 71, 31), crop.getPixel(63, 47))
        } finally { crop.recycle() }
        val rendered = decode(prepared.rendered)
        try {
            assertEquals(768, rendered.width)
            assertTrue(rendered.height >= 80)
            assertEquals(Color.WHITE, rendered.getPixel(0, 0))
        } finally { rendered.recycle() }
    }

    @Test fun roiKeepsFineDetailFromOriginalInsteadOfCroppingPreviewOr1024FullImage() = runBlocking {
        val source = source(2048, 1024) { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            for (x in 512 until 1024) for (y in 256 until 512) bitmap.setPixel(x, y, if (x % 2 == 0) Color.BLACK else Color.WHITE)
        }
        val preview = JevImages.preview(source)
        try { assertEquals(640, preview.width); assertEquals(320, preview.height) }
        finally { preview.recycle() }
        val crop = decode(JevImages.prepare(source, "fine detail", JevCrop(0.25f, 0.25f, 0.5f, 0.5f)).original)
        try {
            assertEquals(512, crop.width)
            assertEquals(256, crop.height)
            repeat(32) { x -> assertEquals(if (x % 2 == 0) Color.BLACK else Color.WHITE, crop.getPixel(x, 100)) }
        } finally { crop.recycle() }
    }

    @Test fun fullImageIsLimitedTo1024WithoutUpscalingSmallSources() = runBlocking {
        val large = decode(JevImages.prepare(source(2048, 1024), "large").original)
        try { assertEquals(1024, large.width); assertEquals(512, large.height) }
        finally { large.recycle() }
        val small = decode(JevImages.prepare(source(64, 48), "small").original)
        try { assertEquals(64, small.width); assertEquals(48, small.height) }
        finally { small.recycle() }
    }

    @Test fun invalidImageTextCropAndUriAreRejected() = runBlocking {
        assertTrue(runCatching { JevImages.preview(JevImages.Source(byteArrayOf(1, 2, 3))) }.isFailure)
        val source = source(64, 48)
        assertTrue(runCatching { JevImages.prepare(source, " ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { JevImages.prepare(source, "x".repeat(JevProtocol.MAX_TEXT + 1)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { JevCrop(Float.NaN, 0f, 1f, 1f) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { JevCrop(0.5f, 0f, 0.5f, 1f) }.exceptionOrNull() is IllegalArgumentException)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(runCatching { JevImages.read(context, Uri.parse("file:///sdcard/image.png")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun cancellationWinsBeforeImageDecode() = runBlocking {
        val invalid = JevImages.Source(byteArrayOf(1, 2, 3))
        for (preview in listOf(true, false)) {
            val error = runCatching {
                withContext(Job()) {
                    currentCoroutineContext().cancel()
                    if (preview) JevImages.preview(invalid) else JevImages.prepare(invalid, "sample")
                }
            }.exceptionOrNull()
            assertTrue("cancellation should precede decode failure", error is CancellationException)
        }
    }

    @Test fun multilineOverflowHasTextSpecificErrorBeforeAnyImageDecode() = runBlocking {
        val invalidImage = JevImages.Source(byteArrayOf(1, 2, 3))
        val text = "x\n".repeat(500)
        assertEquals(JevProtocol.MAX_TEXT, text.length)
        assertTrue(runCatching { JevImages.prepare(invalidImage, text) }.exceptionOrNull() is JevTextLayoutException)
        assertTrue(runCatching { JevImages.prepare(invalidImage, " ") }.exceptionOrNull() is JevTextLayoutException)
        // 普通段落正常渲染；不通过删掉换行规避排版限制。
        val output = decode(JevImages.prepare(source(32, 32), "line one\nline two").rendered)
        try { assertTrue(output.height > 80) } finally { output.recycle() }
    }

    @Test fun sourceOwnsItsBytesAndDigestRemainsStableAcrossCallerMutation() = runBlocking {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val bytes = try { ByteArrayOutputStream().also {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray() } finally { bitmap.recycle() }
        val source = JevImages.Source(bytes)
        val digest = source.contentDigest
        assertEquals(digest, JevImages.Source(bytes.copyOf()).contentDigest)
        bytes.fill(0)
        assertNotEquals(digest, JevImages.Source(bytes).contentDigest)
        assertEquals(digest, source.contentDigest)
        assertTrue(source.decodingBuffer().isReadOnly)
        assertTrue(runCatching { source.decodingBuffer().put(0, 0) }.isFailure)
        val preview = JevImages.preview(source)
        try { assertEquals(16, preview.width) } finally { preview.recycle() }
    }
}
