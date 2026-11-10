package com.wangzan101.mika.jev

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** 可在真机验证APK里的原生程序能被系统执行，不下载模型或读取用户数据。 */
class JevRuntimeTest {
    @Test fun packagedArm64RuntimeStartsWithoutDebuggingPermissions() {
        assumeTrue("arm64-v8a" in Build.SUPPORTED_ABIS)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executable = File(context.applicationInfo.nativeLibraryDir, "libjevserver.so")
        assertTrue(executable.canExecute())
        val process = ProcessBuilder(executable.absolutePath, "--version").redirectErrorStream(true).start()
        try {
            assertTrue("native --version timed out", process.waitFor(20, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            assertTrue(output.isNotBlank())
        } finally { process.destroyForcibly() }
    }
}
