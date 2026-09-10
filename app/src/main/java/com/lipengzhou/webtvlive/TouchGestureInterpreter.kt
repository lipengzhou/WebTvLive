package com.lipengzhou.webtvlive

import kotlin.math.abs

/** Converts a one-pointer playback gesture into deterministic, platform-independent actions. */
class TouchGestureInterpreter(
    private val touchSlopPx: Float,
    private val channelDistancePx: Float,
    private val centerWidthFraction: Float = 0.3f,
    private val axisRatio: Float = 1.2f,
    private val adjustmentGain: Float = 1.15f,
    private val minimumBrightness: Float = 0.05f,
) {
    sealed interface Action {
        data class AdjustBrightness(val value: Float) : Action
        data class AdjustVolume(val value: Float) : Action
        data class Tap(val x: Float) : Action
        data object NextChannel : Action
        data object PreviousChannel : Action
        data object BrightnessFinished : Action
        data object VolumeFinished : Action
    }

    private enum class Mode {
        PENDING,
        BRIGHTNESS,
        VOLUME,
        NEXT_CHANNEL,
        PREVIOUS_CHANNEL,
        IGNORED,
    }

    private data class Gesture(
        val startX: Float,
        val startY: Float,
        val width: Float,
        val height: Float,
        val startBrightness: Float,
        val startVolume: Float,
        var mode: Mode = Mode.PENDING,
    )

    private var gesture: Gesture? = null

    fun begin(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        currentBrightness: Float,
        currentVolume: Float,
    ) {
        if (width <= 0f || height <= 0f) {
            gesture = null
            return
        }
        gesture = Gesture(
            startX = x,
            startY = y,
            width = width,
            height = height,
            startBrightness = currentBrightness,
            startVolume = currentVolume,
        )
    }

    fun update(x: Float, y: Float): Action? {
        val current = gesture ?: return null
        val horizontalDelta = x - current.startX
        val verticalDelta = current.startY - y
        if (current.mode == Mode.PENDING) {
            current.mode = detectMode(current, horizontalDelta, verticalDelta)
        }
        val delta = verticalDelta / current.height * adjustmentGain
        return when (current.mode) {
            Mode.BRIGHTNESS -> {
                val value = (current.startBrightness + delta).coerceIn(minimumBrightness, 1f)
                Action.AdjustBrightness(value)
            }
            Mode.VOLUME -> {
                val value = (current.startVolume + delta).coerceIn(0f, 1f)
                Action.AdjustVolume(value)
            }
            Mode.PENDING, Mode.NEXT_CHANNEL, Mode.PREVIOUS_CHANNEL, Mode.IGNORED -> null
        }
    }

    fun finish(): Action? {
        val current = gesture ?: return null
        gesture = null
        return when (current.mode) {
            Mode.PENDING -> Action.Tap(current.startX)
            Mode.NEXT_CHANNEL -> Action.NextChannel
            Mode.PREVIOUS_CHANNEL -> Action.PreviousChannel
            Mode.BRIGHTNESS -> Action.BrightnessFinished
            Mode.VOLUME -> Action.VolumeFinished
            Mode.IGNORED -> null
        }
    }

    fun cancel() {
        gesture = null
    }

    private fun detectMode(
        current: Gesture,
        horizontalDelta: Float,
        verticalDelta: Float,
    ): Mode {
        val absX = abs(horizontalDelta)
        val absY = abs(verticalDelta)
        if (absX >= touchSlopPx && absX > absY * axisRatio) return Mode.IGNORED
        if (absY < touchSlopPx || absY <= absX * axisRatio) return Mode.PENDING

        val centerWidth = current.width * centerWidthFraction
        val centerStart = (current.width - centerWidth) / 2f
        val centerEnd = centerStart + centerWidth
        if (current.startX in centerStart..centerEnd) {
            if (absY < channelDistancePx) return Mode.PENDING
            return if (verticalDelta > 0f) Mode.NEXT_CHANNEL else Mode.PREVIOUS_CHANNEL
        }
        return if (current.startX < current.width / 2f) Mode.BRIGHTNESS else Mode.VOLUME
    }
}
