package com.wangzan101.mika.cap.phone.display

import org.junit.Assert.*
import org.junit.Test

class DisplayFrameAnalysisTest {
    @Test fun analysisIsLazyAndEachKindSamplesOnlyOncePerInternalFrame() {
        var reads = 0
        val analysis = DisplayFrameAnalysis(DisplayFrameGeometry(120, 240, 0)) { _, _ -> reads++; -1 }
        assertEquals(0, reads)
        assertFalse(analysis.black)
        val afterBlack = reads
        assertFalse(analysis.black)
        assertEquals(afterBlack, reads)
        analysis.signature
        analysis.grid
        val afterAll = reads
        assertTrue(afterAll > afterBlack)
        analysis.signature
        analysis.grid
        assertEquals(afterAll, reads)
    }

    @Test fun newInternalFrameHasNewAnalysisWithoutAnyUiPreview() {
        val geometry = DisplayFrameGeometry(120, 240, 0)
        val black = DisplayFrameAnalysis(geometry) { _, _ -> 0xff000000.toInt() }
        val white = DisplayFrameAnalysis(geometry) { _, _ -> -1 }
        assertTrue(black.black)
        assertFalse(white.black)
        assertTrue(FrameMath.pageChanged(black.signature, white.signature))
        assertTrue(com.wangzan101.mika.core.visual.FrameDiff.compare(black.grid, white.grid)!!.large)
    }
}
