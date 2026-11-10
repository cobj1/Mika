package com.wangzan101.mika.cap.phone.display

import android.graphics.PixelFormat
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 需已授权运行的 helper；实际杀进程→接回验收见 docs/dev/virtual-display-recovery.md。 */
@RunWith(AndroidJUnit4::class)
class DisplayRecoveryDeviceTest {
    @Test fun activeSessionCannotBeAdoptedAndReleaseRemovesOwnership() {
        val shell = DeviceSupport.shell()
        val token = UUID.randomUUID().toString()
        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        var first = -1
        var next = -1
        try {
            first = shell.openDisplaySession(token, reader.surface, 720, 1280, 320)
            assertTrue(first > 0)
            assertEquals(-1, shell.openDisplaySession(token, reader.surface, 720, 1280, 320))
            shell.releaseDisplay(first)
            next = shell.openDisplaySession(token, reader.surface, 720, 1280, 320)
            assertTrue(next > 0)
            assertNotEquals(first, next)
        } finally {
            if (first > 0) shell.releaseDisplay(first)
            if (next > 0) shell.releaseDisplay(next)
            reader.close()
        }
    }

    @Test fun displayNumberIsNotAnAdoptionCredential() {
        val shell = DeviceSupport.shell()
        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        try {
            assertEquals(-1, shell.openDisplaySession("0", reader.surface, 720, 1280, 320))
            assertEquals(-1, shell.openDisplaySession("1", reader.surface, 720, 1280, 320))
        } finally { reader.close() }
    }
}
