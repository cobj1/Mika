package com.wangzan101.mika.cap.phone.display

import org.junit.Assert.*
import org.junit.Test

class DisplayRegionPlanTest {
    @Test fun smallTextRegionKeepsOriginalPixelsWithoutAllocatingScreenSize() {
        val plan = DisplayRegionPlan.of(4000, 8000, listOf(250, 500, 300, 510))
        assertEquals(plan.right - plan.left, plan.width)
        assertEquals(plan.bottom - plan.top, plan.height)
        assertTrue(plan.width * plan.height < 32_000)
        assertTrue(plan.left <= 250 * 4000 / 999.0)
        assertTrue(plan.right >= 300 * 4000 / 999.0)
    }

    @Test fun tinyRegionMapsOcrThroughActualPixelBounds() {
        val plan = DisplayRegionPlan.of(100, 100, listOf(500, 500, 501, 501))
        assertEquals(1, plan.width)
        assertEquals(1, plan.height)
        // 请求框只有 1/999，但实际最少包含完整像素，映回不能错误压缩到原请求范围。
        assertEquals(listOf(499, 499, 510, 510), plan.screenBounds(listOf(0, 0, 999, 999)))
    }

    @Test fun fullImageAndRightBottomEdgeStayBounded() {
        val full = DisplayRegionPlan.of(4000, 8000, listOf(0, 0, 999, 999))
        assertEquals(512, full.width)
        assertEquals(1024, full.height)
        assertEquals(listOf(0, 0, 999, 999), full.screenBounds(listOf(0, 0, 999, 999)))
        val edge = DisplayRegionPlan.of(4000, 8000, listOf(998, 998, 999, 999))
        val bounds = edge.screenBounds(listOf(0, 0, 999, 999))
        assertTrue(bounds[0] <= 998 && bounds[1] <= 998)
        assertEquals(listOf(999, 999), bounds.takeLast(2))
    }
}
