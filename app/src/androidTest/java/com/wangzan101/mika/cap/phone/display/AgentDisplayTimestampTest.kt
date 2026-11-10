package com.wangzan101.mika.cap.phone.display

import android.hardware.display.DisplayManager
import android.media.ImageWriter
import android.view.Display
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class AgentDisplayTimestampTest {
    @Test fun aLateReadKeepsTheProducersFrameTimestamp() = runBlocking {
        lateinit var output: Surface
        val shell = Proxy.newProxyInstance(IMikaShell::class.java.classLoader, arrayOf(IMikaShell::class.java)) { _, method, args ->
            when (method.name) {
                "createDisplay" -> {
                    output = args!![0] as Surface
                    Display.DEFAULT_DISPLAY
                }
                "releaseDisplay" -> null
                else -> error("Unexpected shell call: ${method.name}")
            }
        } as IMikaShell
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = AgentDisplay(shell, 64, 96, 160, context.getSystemService(DisplayManager::class.java))
        try {
            display.setPreviewVisible(true)
            ImageWriter.newInstance(output, 1).use { writer ->
                // The buffer represents an older frame even though it is only consumed now.
                val oldTimestamp = System.nanoTime() - 1_000_000_000L
                writer.queueInputImage(writer.dequeueInputImage().apply { timestamp = oldTimestamp })
                withTimeout(5_000) { display.frames.filterNotNull().first() }
                assertEquals(oldTimestamp, display.lastFrameAt())
                val observed = display.latestObservation(0)
                assertNotNull(observed)
                assertEquals(oldTimestamp, observed!!.timestampNanos)
                assertEquals(64, observed.width)
                assertEquals(96, observed.height)
                assertNull(display.latestObservation(oldTimestamp))
            }
        } finally {
            display.release()
        }
    }

    @Test fun hiddenPreviewKeepsAnalysisFreshAndReopeningPublishesTheLatestInternalFrame() = runBlocking {
        lateinit var output: Surface
        val shell = Proxy.newProxyInstance(IMikaShell::class.java.classLoader, arrayOf(IMikaShell::class.java)) { _, method, args ->
            when (method.name) {
                "createDisplay" -> { output = args!![0] as Surface; Display.DEFAULT_DISPLAY }
                "releaseDisplay" -> null
                else -> error("Unexpected shell call: ${method.name}")
            }
        } as IMikaShell
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = AgentDisplay(shell, 64, 96, 160, context.getSystemService(DisplayManager::class.java))
        try {
            ImageWriter.newInstance(output, 1).use { writer ->
                suspend fun publish(at: Long, value: Byte) {
                    val image = writer.dequeueInputImage()
                    image.timestamp = at
                    val buffer = image.planes[0].buffer
                    while (buffer.hasRemaining()) buffer.put(value)
                    writer.queueInputImage(image)
                    withTimeout(5_000) { while (display.lastFrameAt() != at) delay(10) }
                }
                val first = System.nanoTime()
                publish(first, 0)
                assertNull(display.frames.value)
                assertTrue(display.latestFrameBlack()!!)
                val blackSignature = display.latestFrameSignature()!!
                publish(first + 1, -1)
                assertNull(display.frames.value)
                assertFalse(display.latestFrameBlack()!!)
                assertTrue(FrameMath.pageChanged(blackSignature, display.latestFrameSignature()))
                assertNotNull(display.latestObservation(first))
                val snapshot = display.latestFrameSnapshot()!!
                try {
                    assertEquals(first + 1, snapshot.timestampNanos)
                    assertEquals(android.graphics.Color.WHITE, snapshot.bitmap.getPixel(0, 0))
                } finally { snapshot.bitmap.recycle() }

                display.setPreviewVisible(true)
                val shown = withTimeout(5_000) { display.frames.filterNotNull().first() }
                assertEquals(android.graphics.Color.WHITE, shown.getPixel(0, 0))
                publish(first + 2, 0)
                // 限速窗口内的最后一帧也要到期发布，不能靠下一次重绘触发。
                withTimeout(5_000) { display.frames.filterNotNull().first { FrameMath.isBlack(it.width, it.height, it::getPixel) } }
                display.setPreviewVisible(false)
                withTimeout(5_000) { display.frames.first { it == null } }
                publish(first + 3, -1)
                assertFalse(display.latestFrameBlack()!!)
                assertNull(display.frames.value)
            }
        } finally { display.release() }
        assertNull(display.latestFrameSnapshot())
        assertNull(display.latestFrameSignature())
    }
}
