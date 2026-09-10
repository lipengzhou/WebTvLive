package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGestureInterpreterTest {
    private fun interpreter() = TouchGestureInterpreter(
        touchSlopPx = 10f,
        channelDistancePx = 96f,
    )

    @Test
    fun leftAndRightVerticalGesturesAdjustDifferentValues() {
        val interpreter = interpreter()
        interpreter.begin(100f, 500f, 1_000f, 1_000f, 0.5f, 0.4f)
        val brightness = interpreter.update(102f, 300f)
        assertTrue(brightness is TouchGestureInterpreter.Action.AdjustBrightness)
        assertEquals(0.73f, (brightness as TouchGestureInterpreter.Action.AdjustBrightness).value, 0.01f)

        interpreter.begin(900f, 500f, 1_000f, 1_000f, 0.5f, 0.4f)
        val volume = interpreter.update(898f, 300f)
        assertTrue(volume is TouchGestureInterpreter.Action.AdjustVolume)
        assertEquals(0.63f, (volume as TouchGestureInterpreter.Action.AdjustVolume).value, 0.01f)
    }

    @Test
    fun centerVerticalGestureSwitchesOnlyAfterDistanceThreshold() {
        val interpreter = interpreter()
        interpreter.begin(500f, 500f, 1_000f, 1_000f, 0.5f, 0.4f)
        assertNull(interpreter.update(500f, 450f))
        assertNull(interpreter.update(500f, 350f))
        assertEquals(TouchGestureInterpreter.Action.NextChannel, interpreter.finish())
    }

    @Test
    fun shortGestureIsTapAndHorizontalGestureIsIgnored() {
        val interpreter = interpreter()
        interpreter.begin(200f, 500f, 1_000f, 1_000f, 0.5f, 0.4f)
        assertEquals(TouchGestureInterpreter.Action.Tap(200f), interpreter.finish())

        interpreter.begin(200f, 500f, 1_000f, 1_000f, 0.5f, 0.4f)
        assertNull(interpreter.update(400f, 490f))
        assertNull(interpreter.finish())
    }
}
