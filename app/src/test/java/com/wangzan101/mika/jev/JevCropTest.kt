package com.wangzan101.mika.jev

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class JevCropTest {
    private fun assertBounded(plan: JevCrop.Plan, width: Int, height: Int, maxEdge: Int = 1024) {
        assertTrue(plan.fullWidth in 1..minOf(width, 4096))
        assertTrue(plan.fullHeight in 1..minOf(height, 4096))
        assertTrue(plan.fullWidth.toLong() * plan.fullHeight <= 4L * 1024 * 1024)
        assertTrue(plan.left in 0 until plan.right && plan.right <= plan.fullWidth)
        assertTrue(plan.top in 0 until plan.bottom && plan.bottom <= plan.fullHeight)
        assertTrue(plan.right - plan.left <= maxEdge)
        assertTrue(plan.bottom - plan.top <= maxEdge)
    }

    @Test fun rejectsNonFiniteOutOfRangeAndEmptyCrops() {
        listOf(
            floatArrayOf(Float.NaN, 0f, 1f, 1f), floatArrayOf(0f, 0f, Float.POSITIVE_INFINITY, 1f),
            floatArrayOf(-0.01f, 0f, 1f, 1f), floatArrayOf(0f, 0f, 1.01f, 1f),
            floatArrayOf(0.5f, 0f, 0.5f, 1f), floatArrayOf(0f, 0.6f, 1f, 0.5f),
        ).forEach { a ->
            assertThrows(IllegalArgumentException::class.java) { JevCrop(a[0], a[1], a[2], a[3]) }
        }
        assertThrows(IllegalArgumentException::class.java) { JevCrop.Full.decodePlan(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { JevCrop.Full.decodePlan(100, -1) }
        assertThrows(IllegalArgumentException::class.java) { JevCrop.Full.decodePlan(100, 100, 0) }
    }

    @Test fun fullPortraitAndLandscapeFitWithoutUpscaling() {
        assertEquals(JevCrop.Plan(512, 1024, 0, 0, 512, 1024), JevCrop.Full.decodePlan(2000, 4000))
        assertEquals(JevCrop.Plan(1024, 512, 0, 0, 1024, 512), JevCrop.Full.decodePlan(4000, 2000))
        assertEquals(JevCrop.Plan(200, 400, 0, 0, 200, 400), JevCrop.Full.decodePlan(200, 400))
    }

    @Test fun localTextCropKeepsMorePixelsThanShrinkingWholeScreenshotFirst() {
        val crop = JevCrop(0.1f, 0.4f, 0.9f, 0.5f)
        val result = crop.decodePlan(1000, 4000)
        assertBounded(result, 1000, 4000)
        assertEquals(1000, result.fullWidth)
        assertEquals(4000, result.fullHeight)
        assertTrue(result.right - result.left >= 800)
        assertTrue(result.bottom - result.top >= 400)
    }

    @Test fun tinyRegionCannotCauseAnUnboundedFullImageDecode() {
        val crop = JevCrop(0.5f, 0.5f, 0.500001f, 0.500001f)
        for ((width, height) in listOf(100_000 to 100_000, Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            assertBounded(crop.decodePlan(width, height), width, height)
        }
    }

    @Test fun cropCoordinatesRoundOutwardsAndRemainInsideDecodedImage() {
        val crop = JevCrop(0.101f, 0.203f, 0.709f, 0.811f)
        val p = crop.decodePlan(100, 200)
        assertEquals(JevCrop.Plan(100, 200, 10, 40, 71, 163), p)
        assertBounded(p, 100, 200)
    }

    @Test fun onePixelBudgetAndTinySourceRemainValid() {
        val crop = JevCrop(0.49f, 0.49f, 0.51f, 0.51f)
        assertEquals(JevCrop.Plan(1, 1, 0, 0, 1, 1), crop.decodePlan(4000, 2000, 1))
        assertEquals(JevCrop.Plan(1, 1, 0, 0, 1, 1), crop.decodePlan(1, 1))
    }

    @Test fun roundingNeverExceedsRequestedRoiOrDecodeBudget() {
        val random = Random(7)
        repeat(2000) {
            val left = random.nextFloat() * 0.99f
            val top = random.nextFloat() * 0.99f
            val right = left + (1f - left) * (0.001f + 0.999f * random.nextFloat())
            val bottom = top + (1f - top) * (0.001f + 0.999f * random.nextFloat())
            val width = random.nextInt(1, Int.MAX_VALUE)
            val height = random.nextInt(1, Int.MAX_VALUE)
            val edge = listOf(1, 2, 7, 255, 1024, 8192)[it % 6]
            val crop = JevCrop(left, top, right, bottom)
            assertBounded(crop.decodePlan(width, height, edge), width, height, edge)
        }
    }
}
