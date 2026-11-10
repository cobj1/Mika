package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObservationEncodingTest {
    private fun bitmap() = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)

    @Test fun rejectedPreviewNeverEncodesAndAlwaysRecyclesBitmap() {
        val bitmap = bitmap()
        val result = encodeObservationFrame(bitmap, 123, shouldEncode = {
            assertEquals(123L, it.timestampNanos)
            assertEquals(64, it.width)
            assertEquals(96, it.height)
            assertNotNull(it.grid)
            false
        }, ensureActive = {}, encodePng = { error("unchanged image must not be compressed") })
        assertNull(result)
        assertTrue(bitmap.isRecycled)
    }

    @Test fun acceptedPreviewEncodesTheSelectedPixelsThenRecyclesBitmap() {
        val bitmap = bitmap().apply { eraseColor(android.graphics.Color.RED) }
        val result = encodeObservationFrame(bitmap, 123, shouldEncode = { true }, ensureActive = {})!!
        val decoded = BitmapFactory.decodeByteArray(result.png, 0, result.png.size)
        try {
            assertEquals(123L, result.timestampNanos)
            assertEquals(64, decoded.width)
            assertEquals(96, decoded.height)
            assertEquals(android.graphics.Color.RED, decoded.getPixel(30, 50))
        } finally { decoded.recycle() }
        assertTrue(bitmap.isRecycled)
    }

    @Test fun cancellationAfterSelectionSkipsEncodingAndRecyclesBitmap() {
        val bitmap = bitmap()
        var selected = false
        try {
            encodeObservationFrame(bitmap, 123, shouldEncode = { selected = true; true },
                ensureActive = { if (selected) throw CancellationException() },
                encodePng = { error("cancelled image must not be compressed") })
            fail("must propagate cancellation")
        } catch (_: CancellationException) { assertTrue(bitmap.isRecycled) }
    }

    @Test fun failedEncodingRecyclesBitmap() {
        val bitmap = bitmap()
        try {
            encodeObservationFrame(bitmap, 123, shouldEncode = { true }, ensureActive = {},
                encodePng = { throw IllegalStateException("encoder failed") })
            fail("must propagate encoder failure")
        } catch (_: IllegalStateException) { assertTrue(bitmap.isRecycled) }
    }

    @Test fun failedSelectionRecyclesBitmapWithoutCallingEncoder() {
        val bitmap = bitmap()
        try {
            encodeObservationFrame(bitmap, 123, shouldEncode = { throw IllegalStateException("selection failed") }, ensureActive = {},
                encodePng = { error("rejected image must not be compressed") })
            fail("must propagate selection failure")
        } catch (_: IllegalStateException) { assertTrue(bitmap.isRecycled) }
    }
}
