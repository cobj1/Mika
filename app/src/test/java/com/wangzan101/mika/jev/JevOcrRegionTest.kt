package com.wangzan101.mika.jev

import org.junit.Assert.*
import org.junit.Test

class JevOcrRegionTest {
    @Test fun tinyRegionOfHugeImageMapsThroughActualDecodedPixels() {
        val requested = JevCrop(0.5001f, 0.5001f, 0.5002f, 0.5002f)
        val plan = requested.decodePlan(100_000, 100_000, maxEdge = 2048)
        val actual = JevImageOcr.decodedCrop(plan)
        assertTrue(actual.left <= requested.left)
        assertTrue(actual.top <= requested.top)
        assertTrue(actual.right >= requested.right)
        assertTrue(actual.bottom >= requested.bottom)
        assertNotEquals(requested, actual)
        val result = JevImageOcr.Result(emptyList(), actual)
        assertEquals(actual, result.cropFor(listOf(0, 0, 999, 999)))
    }

    @Test fun candidateBoundsAreRelativeToActualCropWithSmallPadding() {
        val actual = JevCrop(0.25f, 0.1f, 0.75f, 0.9f)
        val result = JevImageOcr.Result(emptyList(), actual)
        val crop = result.cropFor(listOf(100, 200, 700, 800))!!
        assertEquals(0.25f + 0.5f * 97 / 999, crop.left, 0.000001f)
        assertEquals(0.1f + 0.8f * 197 / 999, crop.top, 0.000001f)
        assertEquals(0.25f + 0.5f * 703 / 999, crop.right, 0.000001f)
        assertEquals(0.1f + 0.8f * 803 / 999, crop.bottom, 0.000001f)
        assertNull(result.cropFor(listOf(0, 0, 0, 999)))
        assertNull(result.cropFor(listOf(-1, 0, 999, 999)))
        assertNull(result.cropFor(listOf(0, 0, 999)))
    }
}
