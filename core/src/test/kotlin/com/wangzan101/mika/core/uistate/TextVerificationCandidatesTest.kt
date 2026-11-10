package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.access.AppAccessState
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TextVerificationCandidatesTest {
    private val region = listOf(200, 100, 800, 500)

    @Test fun sameCropCoordinatesMapBackToScreenAndLowConfidenceIsPreserved() {
        val candidates = TextVerificationCandidates.fromOcr(listOf(VisibleTextLine("12.34", listOf(0, 0, 999, 999), 0.2f)), region)
        assertEquals(region, candidates.single().bounds)
        assertEquals(0.2f, candidates.single().ocrConfidence)
        assertEquals("same_frame_ocr", candidates.single().source)
        assertEquals(candidates.single(), TextVerificationCandidates.unique(candidates))
    }

    @Test fun twoRecognizersAgreeingOnSameLineCanBeSelectedButConflictsAreAmbiguous() {
        val agreeing = TextVerificationCandidates.fromOcr(listOf(
            VisibleTextLine("12.34", listOf(10, 20, 900, 400)),
            VisibleTextLine("12.34", listOf(12, 22, 905, 401)),
        ), region)
        assertNotNull(TextVerificationCandidates.unique(agreeing))
        assertNull(TextVerificationCandidates.unique(agreeing + agreeing.first().copy(text = "12.84")))
    }

    @Test fun repeatedTextInSeparateLinesAndDifferentCandidatesDoNotGetJoined() {
        val repeated = TextVerificationCandidates.fromOcr(listOf(
            VisibleTextLine("12.34", listOf(0, 0, 999, 300)),
            VisibleTextLine("12.34", listOf(0, 600, 999, 999)),
        ), region)
        assertEquals(2, repeated.size)
        assertNull(TextVerificationCandidates.unique(repeated))
        assertTrue(TextVerificationCandidates.fromOcr(listOf(VisibleTextLine("x", listOf(0, 0, 0, 0))), region).isEmpty())
    }

    @Test fun accessIsCheckedBeforeScoringAndAgainAfterLongEvaluation() = runTest {
        var pkg = "bank"
        var scores = 0
        val inner = object : ScreenPort by FakeScreen() {
            override suspend fun verifyText(text: String?, region: List<Int>): TextVerification {
                scores++
                pkg = "bank"
                return TextVerification(region, score = 0.99)
            }
        }
        val guarded = AppAccessScreenPort(inner, AppAccessPolicy { AppAccessState(blocked = setOf("bank")) }, packages = { listOf(pkg) })
        assertTrue(runCatching { guarded.verifyText("value", region) }.exceptionOrNull() is AppAccessDenied)
        assertEquals(0, scores)
        pkg = "allowed"
        assertTrue(runCatching { guarded.verifyText("value", region) }.exceptionOrNull() is AppAccessDenied)
        assertEquals(1, scores)
    }
}
