package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Android 位图验证；无需下载模型。 */
@RunWith(AndroidJUnit4::class)
class AgentTextVerificationTest {
    @Test fun cropReadsOriginalRegionAndKeepsSourceOwnedByCaller() {
        val source = Bitmap.createBitmap(999, 999, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        for (x in 333 until 666) for (y in 333 until 666) source.setPixel(x, y, if (x % 2 == 0) Color.BLACK else Color.WHITE)
        try {
            val crop = AgentTextVerification.crop(source, listOf(333, 333, 666, 666))
            try {
                assertEquals(333, crop.width)
                assertEquals(333, crop.height)
                repeat(30) { x -> assertEquals(if ((x + 333) % 2 == 0) Color.BLACK else Color.WHITE, crop.getPixel(x, 100)) }
            } finally { crop.recycle() }
            assertFalse(source.isRecycled)
        } finally { source.recycle() }
    }

    @Test fun fullFrameIsBoundedAndTinyRightBottomRegionRemainsValid() {
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        try {
            val full = AgentTextVerification.crop(source, listOf(0, 0, 999, 999))
            try { assertEquals(1024, full.width); assertEquals(512, full.height) }
            finally { full.recycle() }
            val tiny = AgentTextVerification.crop(source, listOf(998, 998, 999, 999))
            try { assertTrue(tiny.width in 1..3); assertTrue(tiny.height in 1..2) }
            finally { tiny.recycle() }
        } finally { source.recycle() }
    }
}
