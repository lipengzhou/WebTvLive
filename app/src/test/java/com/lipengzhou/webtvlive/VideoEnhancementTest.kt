package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEnhancementTest {

    @Test
    fun fromWireValueRestoresKnownLevel() {
        assertEquals(VideoEnhancement.STANDARD, VideoEnhancement.fromWireValue("standard"))
    }

    @Test
    fun fromWireValueFallsBackToOriginal() {
        assertEquals(VideoEnhancement.ORIGINAL, VideoEnhancement.fromWireValue("unknown"))
        assertEquals(VideoEnhancement.ORIGINAL, VideoEnhancement.fromWireValue(null))
    }
}
