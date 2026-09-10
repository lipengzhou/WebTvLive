package com.lipengzhou.webtvlive

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.lipengzhou.webtvlive.databinding.ActivityMainBinding
import kotlin.math.roundToInt

/** Owns playback gestures, touch overlays and the bottom touch action bar. */
class PlaybackTouchController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val panelsVisible: () -> Boolean,
    private val closePanels: () -> Unit,
    private val onPanelInteraction: () -> Unit,
    private val openChannels: () -> Unit,
    private val openSettings: () -> Unit,
    private val switchChannel: (delta: Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val audioManager = activity.getSystemService(AudioManager::class.java)
    private val interpreter = TouchGestureInterpreter(
        touchSlopPx = ViewConfiguration.get(activity).scaledTouchSlop.toFloat(),
        channelDistancePx = CHANNEL_GESTURE_DISTANCE_DP * activity.resources.displayMetrics.density,
    )
    private var childGestureActive = false
    private var pendingSingleTapX = 0f
    private var pendingSingleTapTime = 0L
    private var playbackBrightness: Float? = null
    private var lastAdjustmentPercent = -1

    private val hideAdjustment = Runnable {
        binding.touchAdjustmentOverlay.visibility = View.GONE
    }
    private val hideControlsRunnable = Runnable(::hideControls)
    private val confirmSingleTap = Runnable {
        pendingSingleTapTime = 0L
        pendingSingleTapX = 0f
        toggleControls()
    }

    init {
        binding.touchControls.setOnClickListener { hideControls() }
        binding.touchChannelButton.setOnClickListener {
            hideControls()
            openChannels()
        }
        binding.touchSettingsButton.setOnClickListener {
            hideControls()
            openSettings()
        }
        binding.touchExitButton.setOnClickListener {
            hideControls()
            activity.finish()
        }
        playbackBrightness = restoreBrightness()
        playbackBrightness?.let(::applyBrightness)
    }

    /** Returns true when the event belongs to the playback touch layer. */
    fun intercept(event: MotionEvent): Boolean {
        if (panelsVisible()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (event.isOutsidePanels()) {
                    closePanels()
                    return true
                }
                onPanelInteraction()
            }
            return false
        }

        val fromControls = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            event.isInside(binding.touchControls).also { childGestureActive = it }
        } else {
            childGestureActive
        }
        if (fromControls) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                childGestureActive = false
            }
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event)
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) cancelGesture() else update(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelGesture()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishGesture()
            else -> return false
        }
        return true
    }

    fun prepareForPanel() {
        cancelPendingSingleTap()
        hideControls()
        hideAdjustmentOverlay()
    }

    fun dispose() {
        interpreter.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    private fun begin(event: MotionEvent) {
        handler.removeCallbacks(hideControlsRunnable)
        lastAdjustmentPercent = -1
        interpreter.begin(
            x = event.x,
            y = event.y,
            width = binding.rootLayout.width.toFloat(),
            height = binding.rootLayout.height.toFloat(),
            currentBrightness = currentBrightness(),
            currentVolume = currentVolumeFraction(),
        )
    }

    private fun update(event: MotionEvent) {
        when (val action = interpreter.update(event.x, event.y)) {
            is TouchGestureInterpreter.Action.AdjustBrightness -> {
                cancelPendingSingleTap()
                hideControls()
                updateBrightness(action.value)
            }
            is TouchGestureInterpreter.Action.AdjustVolume -> {
                cancelPendingSingleTap()
                hideControls()
                updateVolume(action.value)
            }
            else -> Unit
        }
    }

    private fun finishGesture() {
        when (val action = interpreter.finish()) {
            is TouchGestureInterpreter.Action.Tap -> handleTap(action.x)
            TouchGestureInterpreter.Action.BrightnessFinished -> {
                playbackBrightness?.let(::saveBrightness)
                scheduleAdjustmentHide()
            }
            TouchGestureInterpreter.Action.VolumeFinished -> scheduleAdjustmentHide()
            TouchGestureInterpreter.Action.NextChannel -> switchChannel(+1)
            TouchGestureInterpreter.Action.PreviousChannel -> switchChannel(-1)
            is TouchGestureInterpreter.Action.AdjustBrightness,
            is TouchGestureInterpreter.Action.AdjustVolume,
            null -> Unit
        }
    }

    private fun handleTap(x: Float) {
        val width = binding.rootLayout.width
        if (width <= 0) return
        val now = SystemClock.elapsedRealtime()
        val isDoubleTap = pendingSingleTapTime > 0L &&
            now - pendingSingleTapTime <= DOUBLE_TAP_MS &&
            isSameHalf(pendingSingleTapX, x, width)
        handler.removeCallbacks(confirmSingleTap)
        if (isDoubleTap) {
            pendingSingleTapTime = 0L
            pendingSingleTapX = 0f
            hideControls()
            if (x < width / 2f) openChannels() else openSettings()
        } else {
            pendingSingleTapX = x
            pendingSingleTapTime = now
            handler.postDelayed(confirmSingleTap, DOUBLE_TAP_MS)
        }
    }

    private fun updateBrightness(value: Float) {
        val brightness = value.coerceIn(MIN_BRIGHTNESS, 1f)
        applyBrightness(brightness)
        val percent = (brightness * 100).roundToInt().coerceIn(0, 100)
        showAdjustment(
            activity.getString(
                R.string.touch_adjustment_brightness,
                percent,
            ),
            percent,
        )
    }

    private fun applyBrightness(value: Float) {
        val brightness = value.coerceIn(MIN_BRIGHTNESS, 1f)
        playbackBrightness = brightness
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = brightness
        }
    }

    private fun updateVolume(value: Float) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = (value * maxVolume).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        val percent = (volume * 100f / maxVolume).roundToInt().coerceIn(0, 100)
        showAdjustment(
            activity.getString(
                R.string.touch_adjustment_volume,
                percent,
            ),
            percent,
        )
    }

    private fun showAdjustment(text: String, percent: Int) {
        if (lastAdjustmentPercent == percent) return
        lastAdjustmentPercent = percent
        binding.touchAdjustmentOverlay.text = text
        binding.touchAdjustmentOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideAdjustment)
    }

    private fun currentBrightness(): Float {
        val windowBrightness = activity.window.attributes.screenBrightness
        if (windowBrightness in 0f..1f) return windowBrightness.coerceIn(MIN_BRIGHTNESS, 1f)
        val systemBrightness = runCatching {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
        }.getOrDefault((DEFAULT_SYSTEM_BRIGHTNESS * 255).roundToInt())
        return (systemBrightness / 255f).coerceIn(MIN_BRIGHTNESS, 1f)
    }

    private fun currentVolumeFraction(): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat())
            .coerceIn(0f, 1f)
    }

    private fun restoreBrightness(): Float? {
        if (!preferences.contains(KEY_BRIGHTNESS)) return null
        val saved = preferences.getFloat(KEY_BRIGHTNESS, DEFAULT_SYSTEM_BRIGHTNESS)
        return saved.takeIf { it in 0f..1f }?.coerceIn(MIN_BRIGHTNESS, 1f)
    }

    private fun saveBrightness(value: Float) {
        preferences.edit {
            putFloat(KEY_BRIGHTNESS, value.coerceIn(MIN_BRIGHTNESS, 1f))
        }
    }

    private fun scheduleAdjustmentHide() {
        handler.removeCallbacks(hideAdjustment)
        handler.postDelayed(hideAdjustment, ADJUSTMENT_SHOW_MS)
    }

    private fun cancelGesture() {
        interpreter.cancel()
        hideAdjustmentOverlay()
    }

    private fun toggleControls() {
        if (binding.touchControls.isVisible) hideControls() else showControls()
    }

    private fun showControls() {
        binding.touchControls.visibility = View.VISIBLE
        binding.touchControls.bringToFront()
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_SHOW_MS)
    }

    private fun hideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        binding.touchControls.visibility = View.GONE
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTapTime = 0L
        pendingSingleTapX = 0f
        handler.removeCallbacks(confirmSingleTap)
    }

    private fun hideAdjustmentOverlay() {
        handler.removeCallbacks(hideAdjustment)
        binding.touchAdjustmentOverlay.visibility = View.GONE
    }

    private fun MotionEvent.isOutsidePanels(): Boolean {
        val insideMenu = isInside(binding.menuPanel)
        val insideSettings = isInside(binding.settingsPanel)
        return !insideMenu && !insideSettings
    }

    private fun MotionEvent.isInside(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
    }

    private fun isSameHalf(firstX: Float, secondX: Float, width: Int): Boolean =
        (firstX < width / 2f) == (secondX < width / 2f)

    private companion object {
        const val PREFERENCES_NAME = "webtvlive_prefs"
        const val KEY_BRIGHTNESS = "playback_brightness"
        const val DOUBLE_TAP_MS = 300L
        const val ADJUSTMENT_SHOW_MS = 900L
        const val CONTROLS_SHOW_MS = 3_000L
        const val CHANNEL_GESTURE_DISTANCE_DP = 96f
        const val MIN_BRIGHTNESS = 0.05f
        const val DEFAULT_SYSTEM_BRIGHTNESS = 0.5f
    }
}
