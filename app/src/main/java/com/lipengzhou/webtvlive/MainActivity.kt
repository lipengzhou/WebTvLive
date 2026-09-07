package com.lipengzhou.webtvlive

import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.lipengzhou.webtvlive.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * WebTvLive：
 *  - 启动后自动加载频道列表中的默认频道；
 *  - 页面加载完注入全屏脚本，把网页 <video> 铺满整个屏幕；
 *  - 遥控器方向键「上/下」循环换台，「确定」键呼出侧边频道菜单，返回键按两次退出应用。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var browserEngine: BrowserEngine
    private var pageLoadInProgress = false
    private var channelSwitchRequestId = 0L
    private var waitingPlaybackRequestId: Long? = null
    private var activityStartedAt = 0L
    private var nextPlaybackAttemptId = 0L
    private var playbackAttempt: PlaybackAttempt? = null

    // 返回键两次退出
    private var lastBackPressedTime = 0L

    // 记住「上次播放的频道」：应用退出后重开继续播放该台
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // 当前频道下标（遥控器上/下切换）；无记录时默认 CCTV-13 新闻，保持与旧版一致。
    // 这里用的是「所有分类频道拉平后的一维下标」，见 TvCatalog.flatChannels。
    private var currentChannelIndex = DEFAULT_CHANNEL_INDEX
    private var lastSuccessfulChannelIndex = DEFAULT_CHANNEL_INDEX

    // region 侧边菜单状态
    // 菜单是否展开。菜单只是盖在视频上的左侧浮层，展开期间不碰浏览器 View，视频照常播放。
    private var menuVisible = false
    // 当前活动列：左=分类，右=频道。方向键上/下作用在活动列上，左/右在两列间切换。
    private var activeColumn = COLUMN_CHANNEL
    // 右栏当前展示的是哪个分类的频道
    private var menuCategoryIndex = 0

    private lateinit var categoryAdapter: MenuAdapter
    private lateinit var channelAdapter: MenuAdapter
    private var menuInitialized = false
    // endregion

    // region 右侧系统设置面板状态
    private var settingsVisible = false
    private var settingsInitialized = false
    private var settingsActiveColumn = COLUMN_SETTING_VALUE
    private lateinit var settingsCategoryAdapter: MenuAdapter
    private lateinit var settingsValueAdapter: MenuAdapter
    private var videoEnhancement = VideoEnhancement.ORIGINAL
    // endregion

    // region 触屏亮度/音量状态
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var touchGesture: TouchGesture? = null
    private var touchControlChildGestureActive = false
    private var pendingSingleTapX = 0f
    private var pendingSingleTapTime = 0L
    private var playbackBrightness: Float? = null
    // endregion

    // 主线程 Handler：控制频道名浮层自动隐藏、以及换台防抖
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideChannelNameRunnable = Runnable {
        binding.channelName.visibility = View.GONE
    }
    private val hideTouchAdjustmentRunnable = Runnable {
        binding.touchAdjustmentOverlay.visibility = View.GONE
    }
    private val hideTouchControlsRunnable = Runnable {
        hideTouchControls()
    }
    private val autoClosePanelRunnable = Runnable {
        closeMenu()
        closeSettings()
    }
    private val singleTapRunnable = Runnable {
        handleConfirmedSingleTap()
    }
    // 换台防抖：狂按遥控器时不每次都加载，停手后只对最终频道加载一次
    private val loadChannelRunnable = Runnable { loadCurrentChannel() }
    private val playbackTimeoutRunnable = Runnable { handlePlaybackTimeout() }

    private enum class PlaybackStage {
        IN_PAGE,
        DIRECT,
        DIRECT_RETRY,
        FALLBACK,
    }

    private data class PlaybackAttempt(
        val id: Long,
        val channelIndex: Int,
        val stage: PlaybackStage,
        val startedAt: Long,
        var requestId: Long? = null,
    )

    private enum class TouchGestureMode {
        PENDING,
        BRIGHTNESS,
        VOLUME,
        NEXT_CHANNEL,
        PREVIOUS_CHANNEL,
        IGNORED,
    }

    private data class TouchGesture(
        val startX: Float,
        val startY: Float,
        var mode: TouchGestureMode = TouchGestureMode.PENDING,
        var startValue: Float = 0f,
        var lastPercent: Int = -1,
    )

    companion object {
        private const val BACK_EXIT_INTERVAL = 2000L
        private const val CHANNEL_NAME_SHOW_MS = 3000L
        private const val TOUCH_ADJUSTMENT_SHOW_MS = 900L
        private const val TOUCH_CONTROLS_SHOW_MS = 3000L
        private const val PANEL_AUTO_CLOSE_MS = 12_000L
        private const val TOUCH_DOUBLE_TAP_MS = 300L
        private const val CHANNEL_GESTURE_CENTER_WIDTH_FRACTION = 0.3f
        private const val CHANNEL_GESTURE_DISTANCE_DP = 96f
        private const val TOUCH_GESTURE_GAIN = 1.15f
        private const val GESTURE_AXIS_RATIO = 1.2f
        private const val MIN_PLAYBACK_BRIGHTNESS = 0.05f
        private const val DEFAULT_SYSTEM_BRIGHTNESS = 0.5f
        // 换台防抖：停止按键 600ms 后才真正加载，避免连续切台把每个中间台都请求一遍被 CCTV 限流
        private const val CHANNEL_SWITCH_DEBOUNCE_MS = 600L
        // 已加载页面内换台通常很快；超时后改用该频道的官网 pid 页面重新建链。
        private const val IN_PAGE_PLAYBACK_TIMEOUT_MS = 18_000L
        // 低性能电视冷启动实测正常首播也可能接近 30 秒，因此整页加载给更宽松的窗口。
        private const val DIRECT_PLAYBACK_TIMEOUT_MS = 35_000L
        // 页面资源已缓存后的同频道重试应明显更快，避免失败时继续长时间等待。
        private const val DIRECT_RETRY_TIMEOUT_MS = 25_000L
        private const val FALLBACK_PLAYBACK_TIMEOUT_MS = 30_000L
        private const val DEFAULT_CHANNEL_INDEX = 13
        private const val STABLE_FALLBACK_SITE_NAME = "CCTV9"
        private const val SECONDARY_FALLBACK_SITE_NAME = "CCTV10"
        // 记住上次频道用的 SharedPreferences
        private const val PREFS_NAME = "webtvlive_prefs"
        private const val KEY_LAST_CHANNEL = "last_channel_index"
        private const val KEY_LAST_SUCCESSFUL_CHANNEL = "last_successful_channel_index"
        private const val KEY_VIDEO_ENHANCEMENT = "video_enhancement"
        private const val KEY_PLAYBACK_BRIGHTNESS = "playback_brightness"
        // 侧边菜单两列
        private const val COLUMN_CATEGORY = 0
        private const val COLUMN_CHANNEL = 1
        private const val COLUMN_SETTING_CATEGORY = 0
        private const val COLUMN_SETTING_VALUE = 1
        private const val TAG = "WebTvLive"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        activityStartedAt = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveFullscreen()
        keepScreenOn()
        setupTouchControls()

        lastSuccessfulChannelIndex = restoreLastSuccessfulChannelIndex()
        currentChannelIndex = lastSuccessfulChannelIndex
        videoEnhancement = restoreVideoEnhancement()
        playbackBrightness = restorePlaybackBrightness()
        playbackBrightness?.let { applyPlaybackBrightness(it) }
        Log.i(TAG, "StartupTiming: activity_ready elapsed=${startupElapsed()}ms")
        createAndAttachBrowserEngine()
    }

    // region 浏览器内核
    private fun createAndAttachBrowserEngine() {
        browserEngine = createBrowserEngine(this)
        browserEngine.attach(
            binding.webContainer,
            object : BrowserEngine.Listener {
                override fun onReady() {
                    if (isFinishing || isDestroyed) return
                    Log.i(TAG, "Browser engine ready; elapsed=${startupElapsed()}ms")
                    browserEngine.applyVideoEnhancement(videoEnhancement)
                    loadCurrentChannel()
                }

                override fun onPageStarted(url: String) {
                    pageLoadInProgress = true
                    Log.i(TAG, "Browser page start: $url")
                    if (url.startsWith(TvCatalog.YANGSHIPIN_HOME_URL)) {
                        Log.i(TAG, "StartupTiming: page_started elapsed=${startupElapsed()}ms")
                    }
                }

                override fun onPageStopped(success: Boolean) {
                    pageLoadInProgress = false
                    Log.i(TAG, "Browser page stop: success=$success")
                }

                override fun onPlaybackReady(requestId: Long) {
                    handlePlaybackReady(requestId)
                }

                override fun onChannelSelected(channel: String) {
                    Log.i(TAG, "Yangshipin channel selected: $channel")
                }

                override fun onChannelNotFound(channel: String) {
                    Log.e(TAG, "Yangshipin channel not found: $channel")
                }

                override fun onDiagnostic(message: String) {
                    Log.i(TAG, message)
                }

                override fun onCrash() {
                    Log.e(TAG, "Browser content process crashed")
                }
            },
        )
    }

    private fun handlePlaybackReady(requestId: Long) {
        val attempt = playbackAttempt
        if (requestId == waitingPlaybackRequestId && attempt?.requestId == requestId) {
            uiHandler.removeCallbacks(playbackTimeoutRunnable)
            waitingPlaybackRequestId = null
            playbackAttempt = null
            currentChannelIndex = attempt.channelIndex
            lastSuccessfulChannelIndex = attempt.channelIndex
            saveSuccessfulChannelIndex(attempt.channelIndex)
            binding.loadingText.visibility = View.GONE
            Log.i(
                TAG,
                "Playback ready; loading overlay hidden: request=$requestId, " +
                    "channel=${TvCatalog.flatChannels[attempt.channelIndex].siteName}",
            )
            Log.i(
                TAG,
                "StartupTiming: playback_ready total=${startupElapsed()}ms, " +
                    "attempt=${SystemClock.elapsedRealtime() - attempt.startedAt}ms, " +
                    "stage=${attempt.stage}",
            )
        } else {
            Log.i(
                TAG,
                "Ignored stale playing message: request=$requestId, " +
                    "waiting=$waitingPlaybackRequestId",
            )
        }
    }
    // endregion

    // region 全屏 / 常亮
    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun keepScreenOn() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveFullscreen()
    }
    // endregion

    // region 触屏亮度/音量
    private fun setupTouchControls() {
        binding.touchControls.setOnClickListener { hideTouchControls() }
        binding.touchChannelButton.setOnClickListener {
            hideTouchControls()
            openMenu()
        }
        binding.touchSettingsButton.setOnClickListener {
            hideTouchControls()
            openSettings()
        }
        binding.touchExitButton.setOnClickListener {
            hideTouchControls()
            finish()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!::binding.isInitialized) {
            return super.dispatchTouchEvent(event)
        }
        if (menuVisible || settingsVisible) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (event.isOutsideVisiblePanel()) {
                    closeMenu()
                    closeSettings()
                    return true
                }
                schedulePanelAutoClose()
            }
            return super.dispatchTouchEvent(event)
        }

        val fromTouchControls = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (event.isInsideView(binding.touchControls)) {
                touchControlChildGestureActive = true
                true
            } else {
                false
            }
        } else {
            touchControlChildGestureActive
        }
        if (fromTouchControls) {
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                touchControlChildGestureActive = false
            }
            return super.dispatchTouchEvent(event)
        }
        return if (handlePlaybackTouch(event)) true else super.dispatchTouchEvent(event)
    }

    private fun handlePlaybackTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginPlaybackTouch(event)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) {
                    cancelPlaybackTouch()
                    return true
                }
                updatePlaybackTouch(event)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPlaybackTouch()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishPlaybackTouch()
                return true
            }
        }
        return false
    }

    private fun MotionEvent.isInsideView(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val rawX = rawX
        val rawY = rawY
        return rawX >= location[0] &&
            rawX <= location[0] + view.width &&
            rawY >= location[1] &&
            rawY <= location[1] + view.height
    }

    private fun MotionEvent.isOutsideVisiblePanel(): Boolean {
        val insideMenu = menuVisible && isInsideView(binding.menuPanel)
        val insideSettings = settingsVisible && isInsideView(binding.settingsPanel)
        return !insideMenu && !insideSettings
    }

    private fun beginPlaybackTouch(event: MotionEvent) {
        uiHandler.removeCallbacks(hideTouchControlsRunnable)
        val rootWidth = binding.rootLayout.width
        if (rootWidth <= 0) return
        touchGesture = TouchGesture(
            startX = event.x,
            startY = event.y,
        )
    }

    private fun updatePlaybackTouch(event: MotionEvent) {
        val gesture = touchGesture ?: return
        val horizontalDelta = event.x - gesture.startX
        val verticalDelta = gesture.startY - event.y

        if (gesture.mode == TouchGestureMode.PENDING) {
            gesture.mode = detectTouchGestureMode(
                startX = gesture.startX,
                horizontalDelta = horizontalDelta,
                verticalDelta = verticalDelta,
            )
            when (gesture.mode) {
                TouchGestureMode.BRIGHTNESS -> {
                    cancelPendingSingleTap()
                    gesture.startValue = currentPlaybackBrightness()
                    hideTouchControls()
                }
                TouchGestureMode.VOLUME -> {
                    cancelPendingSingleTap()
                    gesture.startValue = currentMusicVolumeFraction()
                    hideTouchControls()
                }
                TouchGestureMode.NEXT_CHANNEL, TouchGestureMode.PREVIOUS_CHANNEL -> {
                    cancelPendingSingleTap()
                    hideTouchControls()
                }
                TouchGestureMode.IGNORED, TouchGestureMode.PENDING -> Unit
            }
        }

        if (
            gesture.mode == TouchGestureMode.PENDING ||
            gesture.mode == TouchGestureMode.IGNORED ||
            gesture.mode == TouchGestureMode.NEXT_CHANNEL ||
            gesture.mode == TouchGestureMode.PREVIOUS_CHANNEL
        ) {
            return
        }

        val rootHeight = binding.rootLayout.height.coerceAtLeast(1)
        val nextValue = (
            gesture.startValue +
                verticalDelta / rootHeight * TOUCH_GESTURE_GAIN
            ).coerceIn(0f, 1f)

        when (gesture.mode) {
            TouchGestureMode.BRIGHTNESS -> updatePlaybackBrightness(nextValue, gesture)
            TouchGestureMode.VOLUME -> updateMusicVolume(nextValue, gesture)
            TouchGestureMode.PENDING, TouchGestureMode.NEXT_CHANNEL,
            TouchGestureMode.PREVIOUS_CHANNEL, TouchGestureMode.IGNORED -> Unit
        }
    }

    private fun detectTouchGestureMode(
        startX: Float,
        horizontalDelta: Float,
        verticalDelta: Float,
    ): TouchGestureMode {
        val absX = abs(horizontalDelta)
        val absY = abs(verticalDelta)
        val rootWidth = binding.rootLayout.width
        val channelMode = detectCenterChannelGesture(startX, verticalDelta, absY)

        if (absX >= touchSlop && absX > absY * GESTURE_AXIS_RATIO) {
            return TouchGestureMode.IGNORED
        }

        if (absY >= touchSlop && absY > absX * GESTURE_AXIS_RATIO) {
            if (channelMode != null) return channelMode
            return if (startX < rootWidth / 2f) {
                TouchGestureMode.BRIGHTNESS
            } else {
                TouchGestureMode.VOLUME
            }
        }

        return TouchGestureMode.PENDING
    }

    private fun detectCenterChannelGesture(
        startX: Float,
        verticalDelta: Float,
        absY: Float,
    ): TouchGestureMode? {
        val rootWidth = binding.rootLayout.width
        val centerWidth = rootWidth * CHANNEL_GESTURE_CENTER_WIDTH_FRACTION
        val centerStart = (rootWidth - centerWidth) / 2f
        val centerEnd = centerStart + centerWidth
        if (startX !in centerStart..centerEnd) {
            return null
        }
        if (absY < CHANNEL_GESTURE_DISTANCE_DP.dp()) return TouchGestureMode.PENDING
        return if (verticalDelta > 0f) {
            TouchGestureMode.NEXT_CHANNEL
        } else {
            TouchGestureMode.PREVIOUS_CHANNEL
        }
    }

    private fun updatePlaybackBrightness(value: Float, gesture: TouchGesture) {
        val brightness = value.coerceIn(MIN_PLAYBACK_BRIGHTNESS, 1f)
        applyPlaybackBrightness(brightness)
        val percent = (brightness * 100).roundToInt().coerceIn(0, 100)
        showTouchAdjustment(TouchGestureMode.BRIGHTNESS, percent, gesture)
    }

    private fun applyPlaybackBrightness(value: Float) {
        val brightness = value.coerceIn(MIN_PLAYBACK_BRIGHTNESS, 1f)
        playbackBrightness = brightness
        val attributes = window.attributes
        attributes.screenBrightness = brightness
        window.attributes = attributes
    }

    private fun updateMusicVolume(value: Float, gesture: TouchGesture) {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            .coerceAtLeast(1)
        val volume = (value * maxVolume).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        val percent = (volume * 100f / maxVolume).roundToInt().coerceIn(0, 100)
        showTouchAdjustment(TouchGestureMode.VOLUME, percent, gesture)
    }

    private fun showTouchAdjustment(
        type: TouchGestureMode,
        percent: Int,
        gesture: TouchGesture,
    ) {
        if (gesture.lastPercent == percent) return
        gesture.lastPercent = percent
        binding.touchAdjustmentOverlay.text = when (type) {
            TouchGestureMode.BRIGHTNESS -> getString(
                R.string.touch_adjustment_brightness,
                percent,
            )
            TouchGestureMode.VOLUME -> getString(R.string.touch_adjustment_volume, percent)
            TouchGestureMode.PENDING, TouchGestureMode.NEXT_CHANNEL,
            TouchGestureMode.PREVIOUS_CHANNEL, TouchGestureMode.IGNORED -> ""
        }
        binding.touchAdjustmentOverlay.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideTouchAdjustmentRunnable)
    }

    private fun finishPlaybackTouch() {
        val gesture = touchGesture
        val completedMode = gesture?.mode
        touchGesture = null

        when (completedMode) {
            TouchGestureMode.PENDING -> handlePlaybackTap(gesture?.startX ?: 0f)
            TouchGestureMode.BRIGHTNESS -> {
                playbackBrightness?.let { savePlaybackBrightness(it) }
                scheduleTouchAdjustmentOverlayHide()
            }
            TouchGestureMode.VOLUME -> scheduleTouchAdjustmentOverlayHide()
            TouchGestureMode.NEXT_CHANNEL -> {
                Log.i(TAG, "Touch channel gesture: next")
                switchChannel(+1)
            }
            TouchGestureMode.PREVIOUS_CHANNEL -> {
                Log.i(TAG, "Touch channel gesture: previous")
                switchChannel(-1)
            }
            TouchGestureMode.IGNORED, null -> Unit
        }
    }

    private fun handlePlaybackTap(x: Float) {
        val rootWidth = binding.rootLayout.width
        if (rootWidth <= 0) return
        val now = SystemClock.elapsedRealtime()
        val previousX = pendingSingleTapX
        val previousTime = pendingSingleTapTime
        val isDoubleTap = previousTime > 0L &&
            now - previousTime <= TOUCH_DOUBLE_TAP_MS &&
            isSameHalf(previousX, x)

        uiHandler.removeCallbacks(singleTapRunnable)
        if (isDoubleTap) {
            pendingSingleTapTime = 0L
            pendingSingleTapX = 0f
            hideTouchControls()
            if (x < rootWidth / 2f) {
                openMenu()
            } else {
                openSettings()
            }
        } else {
            pendingSingleTapX = x
            pendingSingleTapTime = now
            uiHandler.postDelayed(singleTapRunnable, TOUCH_DOUBLE_TAP_MS)
        }
    }

    private fun handleConfirmedSingleTap() {
        pendingSingleTapTime = 0L
        pendingSingleTapX = 0f
        toggleTouchControls()
    }

    private fun isSameHalf(firstX: Float, secondX: Float): Boolean {
        val rootWidth = binding.rootLayout.width
        if (rootWidth <= 0) return false
        return (firstX < rootWidth / 2f) == (secondX < rootWidth / 2f)
    }

    private fun scheduleTouchAdjustmentOverlayHide() {
        uiHandler.removeCallbacks(hideTouchAdjustmentRunnable)
        uiHandler.postDelayed(hideTouchAdjustmentRunnable, TOUCH_ADJUSTMENT_SHOW_MS)
    }

    private fun cancelPlaybackTouch() {
        touchGesture = null
        hideTouchAdjustment()
    }

    private fun toggleTouchControls() {
        if (binding.touchControls.visibility == View.VISIBLE) {
            hideTouchControls()
        } else {
            showTouchControls()
        }
    }

    private fun showTouchControls() {
        binding.touchControls.visibility = View.VISIBLE
        binding.touchControls.bringToFront()
        uiHandler.removeCallbacks(hideTouchControlsRunnable)
        uiHandler.postDelayed(hideTouchControlsRunnable, TOUCH_CONTROLS_SHOW_MS)
    }

    private fun hideTouchControls() {
        uiHandler.removeCallbacks(hideTouchControlsRunnable)
        binding.touchControls.visibility = View.GONE
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTapTime = 0L
        pendingSingleTapX = 0f
        uiHandler.removeCallbacks(singleTapRunnable)
    }

    private fun hideTouchAdjustment() {
        uiHandler.removeCallbacks(hideTouchAdjustmentRunnable)
        binding.touchAdjustmentOverlay.visibility = View.GONE
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    private fun schedulePanelAutoClose() {
        if (!menuVisible && !settingsVisible) return
        uiHandler.removeCallbacks(autoClosePanelRunnable)
        uiHandler.postDelayed(autoClosePanelRunnable, PANEL_AUTO_CLOSE_MS)
    }

    private fun cancelPanelAutoClose() {
        uiHandler.removeCallbacks(autoClosePanelRunnable)
    }

    private fun currentPlaybackBrightness(): Float {
        val windowBrightness = window.attributes.screenBrightness
        if (windowBrightness in 0f..1f) {
            return windowBrightness.coerceIn(MIN_PLAYBACK_BRIGHTNESS, 1f)
        }
        val systemBrightness = runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault((DEFAULT_SYSTEM_BRIGHTNESS * 255).roundToInt())
        return (systemBrightness / 255f).coerceIn(MIN_PLAYBACK_BRIGHTNESS, 1f)
    }

    private fun currentMusicVolumeFraction(): Float {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            .coerceAtLeast(1)
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat())
            .coerceIn(0f, 1f)
    }

    private fun restorePlaybackBrightness(): Float? {
        if (!prefs.contains(KEY_PLAYBACK_BRIGHTNESS)) return null
        val saved = prefs.getFloat(KEY_PLAYBACK_BRIGHTNESS, DEFAULT_SYSTEM_BRIGHTNESS)
        return if (saved in 0f..1f) {
            saved.coerceIn(MIN_PLAYBACK_BRIGHTNESS, 1f)
        } else {
            null
        }
    }

    private fun savePlaybackBrightness(value: Float) {
        prefs.edit()
            .putFloat(KEY_PLAYBACK_BRIGHTNESS, value.coerceIn(MIN_PLAYBACK_BRIGHTNESS, 1f))
            .apply()
    }
    // endregion

    // region 遥控器按键：菜单开合 / 上下换台 / 返回退出
    //
    // 关键：必须在 dispatchKeyEvent 里拦截，而不是 onKeyDown。
    // onKeyDown 只是「焦点浏览器 View 没消费按键时」才回调的兜底；方向键会先进网页：
    //  - 左/右让网页滚动或移动焦点 —— 表现为「视频画面移动」；
    //  - 焦点一旦进了网页，后续上/下也可能被网页吃掉 —— 表现为「换台失灵」。
    // 在 dispatchKeyEvent 提前吞掉这些键，浏览器永远拿不到，两个问题一并解决。
    // 菜单展开时，同一批方向键改为在菜单内导航（此时浏览器 View 仍在后面正常播放）。
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (isRemoteControlKey(keyCode)) {
            // 只在按下时执行动作；抬起事件也一并吞掉，避免只截按下、抬起漏给 WebView
            if (event.action == KeyEvent.ACTION_DOWN) {
                when {
                    settingsVisible -> handleSettingsKeyDown(keyCode)
                    menuVisible -> handleMenuKeyDown(keyCode)
                    else -> handleRemoteKeyDown(keyCode)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** 本 App 需要独占的遥控器键（其余如音量键放行给系统）。 */
    private fun isRemoteControlKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_TV_CONTENTS_MENU -> true
        else -> false
    }

    /** 标准播放态下的按键。 */
    private fun handleRemoteKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> handleBack()
            // 上：下一个频道（cctv1 -> cctv2 …，到末尾循环回第一个）
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> switchChannel(+1)
            // 下：上一个频道（cctv2 -> cctv1 …，到开头循环回最后一个）
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> switchChannel(-1)
            // OK/中央键：呼出侧边频道菜单
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> openMenu()
            // MENU/设置键：从右侧呼出系统设置面板
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU -> openSettings()
            // 左/右：本 App 不做网页内导航，吞掉即可，防止网页滚动 / 移动焦点
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { /* no-op：故意屏蔽 */ }
        }
    }

    /** 按 delta（+1/-1）循环切换频道。只更新下标 + 浮层反馈，真正加载走防抖，避免狂按时逐台请求被限流。 */
    private fun switchChannel(delta: Int) {
        val size = TvCatalog.flatChannels.size
        currentChannelIndex = ((currentChannelIndex + delta) % size + size) % size
        val channel = TvCatalog.flatChannels[currentChannelIndex]
        // 连按浏览期间保留当前台画面、只滚动更新浮层；不马上盖遮罩/loadUrl。
        // 遮罩留到真正加载时（loadCurrentChannel）再显示——那时旧 video 已被销毁，遮罩才盖得住。
        showChannelName(channel.name)
        // 防抖：停手 CHANNEL_SWITCH_DEBOUNCE_MS 后只加载最终停留的这一台
        uiHandler.removeCallbacks(loadChannelRunnable)
        uiHandler.postDelayed(loadChannelRunnable, CHANNEL_SWITCH_DEBOUNCE_MS)
    }

    /**
     * 切换当前频道。页面适配脚本可用时优先在站内点击频道项；不可用时整页直达 pid 页面。
     */
    private fun loadCurrentChannel() {
        // 首次加载可能绕过防抖直接进来，这里清一次待执行的防抖任务，避免重复加载
        uiHandler.removeCallbacks(loadChannelRunnable)
        cancelPlaybackAttempt()
        val channel = TvCatalog.flatChannels[currentChannelIndex]
        showChannelName(channel.name)

        if (!browserEngine.canSwitchInPage()) {
            // 首次启动直接进入目标频道页面，避免先初始化 CCTV-1、再重建目标播放器。
            startDirectLoad(currentChannelIndex, PlaybackStage.DIRECT)
            return
        }

        val requestId = ++channelSwitchRequestId
        val attempt = startPlaybackAttempt(
            channelIndex = currentChannelIndex,
            stage = PlaybackStage.IN_PAGE,
            timeoutMs = IN_PAGE_PLAYBACK_TIMEOUT_MS,
        )
        attempt.requestId = requestId
        waitingPlaybackRequestId = requestId
        binding.loadingText.visibility = View.VISIBLE
        if (browserEngine.switchChannel(channel, requestId)) {
            Log.i(
                TAG,
                "Requested in-page channel switch: ${channel.siteName}, request=$requestId, " +
                    "stage=${attempt.stage}",
            )
            return
        }

        startDirectLoad(currentChannelIndex, PlaybackStage.DIRECT)
    }

    private fun startDirectLoad(channelIndex: Int, stage: PlaybackStage) {
        val channel = TvCatalog.flatChannels[channelIndex]
        cancelPlaybackAttempt()
        currentChannelIndex = channelIndex
        binding.loadingText.visibility = View.VISIBLE
        val attempt = startPlaybackAttempt(
            channelIndex = channelIndex,
            stage = stage,
            timeoutMs = when (stage) {
                PlaybackStage.IN_PAGE -> IN_PAGE_PLAYBACK_TIMEOUT_MS
                PlaybackStage.DIRECT -> DIRECT_PLAYBACK_TIMEOUT_MS
                PlaybackStage.DIRECT_RETRY -> DIRECT_RETRY_TIMEOUT_MS
                PlaybackStage.FALLBACK -> FALLBACK_PLAYBACK_TIMEOUT_MS
            },
        )
        val requestId = ++channelSwitchRequestId
        attempt.requestId = requestId
        waitingPlaybackRequestId = requestId
        browserEngine.loadChannel(channel, requestId)
        Log.i(
            TAG,
            "Direct channel page load: ${channel.siteName}, pid=${channel.pid}, " +
                "stage=$stage, attempt=${attempt.id}",
        )
    }

    private fun startPlaybackAttempt(
        channelIndex: Int,
        stage: PlaybackStage,
        timeoutMs: Long,
    ): PlaybackAttempt {
        cancelPlaybackAttempt()
        val attempt = PlaybackAttempt(
            id = ++nextPlaybackAttemptId,
            channelIndex = channelIndex,
            stage = stage,
            startedAt = SystemClock.elapsedRealtime(),
        )
        playbackAttempt = attempt
        uiHandler.postDelayed(playbackTimeoutRunnable, timeoutMs)
        Log.i(
            TAG,
            "Playback attempt started: id=${attempt.id}, " +
                "channel=${TvCatalog.flatChannels[channelIndex].siteName}, " +
                "stage=$stage, timeout=${timeoutMs}ms",
        )
        return attempt
    }

    private fun cancelPlaybackAttempt() {
        uiHandler.removeCallbacks(playbackTimeoutRunnable)
        playbackAttempt = null
        waitingPlaybackRequestId = null
    }

    private fun handlePlaybackTimeout() {
        val attempt = playbackAttempt ?: return
        val channel = TvCatalog.flatChannels[attempt.channelIndex]
        Log.w(
            TAG,
            "Playback timeout: id=${attempt.id}, channel=${channel.siteName}, " +
                "stage=${attempt.stage}, elapsed=${SystemClock.elapsedRealtime() - attempt.startedAt}ms",
        )
        when (attempt.stage) {
            PlaybackStage.IN_PAGE -> {
                // 页内播放器可能进入无法恢复的媒体建链状态；只重载一次目标频道官网页面。
                startDirectLoad(attempt.channelIndex, PlaybackStage.DIRECT)
            }

            PlaybackStage.DIRECT -> {
                Toast.makeText(this, R.string.channel_timeout_retry, Toast.LENGTH_SHORT).show()
                startDirectLoad(attempt.channelIndex, PlaybackStage.DIRECT_RETRY)
            }

            PlaybackStage.DIRECT_RETRY -> {
                val fallbackIndex = fallbackChannelIndex(attempt.channelIndex)
                if (fallbackIndex == attempt.channelIndex) {
                    stopAutomaticRecovery()
                } else {
                    val fallback = TvCatalog.flatChannels[fallbackIndex]
                    Toast.makeText(
                        this,
                        getString(R.string.channel_timeout_fallback, fallback.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    showChannelName(fallback.name)
                    startDirectLoad(fallbackIndex, PlaybackStage.FALLBACK)
                }
            }

            PlaybackStage.FALLBACK -> stopAutomaticRecovery()
        }
    }

    private fun fallbackChannelIndex(failedIndex: Int): Int {
        if (lastSuccessfulChannelIndex != failedIndex) return lastSuccessfulChannelIndex
        val stableIndex = TvCatalog.indexOfSiteName(STABLE_FALLBACK_SITE_NAME)
        if (stableIndex in TvCatalog.flatChannels.indices && stableIndex != failedIndex) {
            return stableIndex
        }
        val secondaryIndex = TvCatalog.indexOfSiteName(SECONDARY_FALLBACK_SITE_NAME)
        return if (secondaryIndex in TvCatalog.flatChannels.indices) secondaryIndex else failedIndex
    }

    private fun stopAutomaticRecovery() {
        cancelPlaybackAttempt()
        binding.loadingText.visibility = View.VISIBLE
        Toast.makeText(this, R.string.channel_timeout_manual, Toast.LENGTH_LONG).show()
        Log.e(TAG, "Automatic playback recovery exhausted; waiting for manual channel switch")
    }

    /** 读取最近一次真正收到 playing 的频道；兼容旧版本保存的频道下标。 */
    private fun restoreLastSuccessfulChannelIndex(): Int {
        val saved = if (prefs.contains(KEY_LAST_SUCCESSFUL_CHANNEL)) {
            prefs.getInt(KEY_LAST_SUCCESSFUL_CHANNEL, DEFAULT_CHANNEL_INDEX)
        } else {
            prefs.getInt(KEY_LAST_CHANNEL, DEFAULT_CHANNEL_INDEX)
        }
        return if (saved in TvCatalog.flatChannels.indices) saved else DEFAULT_CHANNEL_INDEX
    }

    /** 只在目标频道真正出画面后持久化，避免失败频道污染下一次冷启动。 */
    private fun saveSuccessfulChannelIndex(index: Int) {
        prefs.edit()
            .putInt(KEY_LAST_SUCCESSFUL_CHANNEL, index)
            .putInt(KEY_LAST_CHANNEL, index)
            .apply()
    }

    private fun restoreVideoEnhancement(): VideoEnhancement =
        VideoEnhancement.fromWireValue(prefs.getString(KEY_VIDEO_ENHANCEMENT, null))

    private fun startupElapsed(): Long = SystemClock.elapsedRealtime() - activityStartedAt

    /** 在屏幕角落短暂显示频道名，便于确认当前台。 */
    private fun showChannelName(name: String) {
        binding.channelName.text = name
        binding.channelName.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideChannelNameRunnable)
        uiHandler.postDelayed(hideChannelNameRunnable, CHANNEL_NAME_SHOW_MS)
    }

    private fun handleBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressedTime < BACK_EXIT_INTERVAL) {
            finish()
        } else {
            lastBackPressedTime = now
            Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show()
        }
    }
    // endregion

    // region 侧边频道菜单
    /** 初始化左右两个列表：左=分类，右=当前分类下的频道。只建一次。 */
    private fun setupMenu() {
        if (menuInitialized) return
        menuInitialized = true
        categoryAdapter = MenuAdapter(
            itemLayoutRes = R.layout.item_category,
        ) { position -> onCategoryChosen(position) }

        channelAdapter = MenuAdapter(
            itemLayoutRes = R.layout.item_channel,
        ) { position -> onChannelChosen(position) }

        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter
        // 遥控器长按会快速刷新旧/新选中行；关闭默认交叉淡变，避免高亮看起来闪烁。
        binding.categoryList.itemAnimator = null
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter
        binding.channelList.itemAnimator = null

        categoryAdapter.submit(TvCatalog.categories.map { it.name }, keepIndex = 0)
    }

    /** 呼出菜单：把左右两列定位到「当前正在播放的频道」，右列聚焦，视频保持播放。 */
    private fun openMenu() {
        if (menuVisible) return
        cancelPendingSingleTap()
        hideTouchControls()
        hideTouchAdjustment()
        closeSettings()
        setupMenu()
        menuVisible = true

        val (catIndex, chIndex) = TvCatalog.locate(currentChannelIndex)
        menuCategoryIndex = catIndex
        activeColumn = COLUMN_CHANNEL

        categoryAdapter.setSelected(catIndex)
        channelAdapter.submit(
            TvCatalog.categories[catIndex].channels.map { it.name },
            keepIndex = chIndex,
        )
        syncColumnActive()

        binding.menuPanel.visibility = View.VISIBLE
        binding.categoryList.scrollToPosition(catIndex)
        binding.channelList.scrollToPosition(chIndex)
        schedulePanelAutoClose()
    }

    /** 关闭菜单。视频一直在后面播放，这里只是收起浮层。 */
    private fun closeMenu() {
        if (!menuVisible) return
        menuVisible = false
        binding.menuPanel.visibility = View.GONE
        if (!settingsVisible) cancelPanelAutoClose()
    }

    /** 菜单展开态下的按键：上下在活动列内移动，左右切列，OK 选中，返回关闭。 */
    private fun handleMenuKeyDown(keyCode: Int) {
        schedulePanelAutoClose()
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> closeMenu()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU -> {
                closeMenu()
                openSettings()
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (moveSelection(-1)) playMenuSound(SoundEffectConstants.NAVIGATION_UP)
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (moveSelection(+1)) playMenuSound(SoundEffectConstants.NAVIGATION_DOWN)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focusColumn(COLUMN_CATEGORY)) {
                    playMenuSound(SoundEffectConstants.NAVIGATION_LEFT)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusColumn(COLUMN_CHANNEL)) {
                    playMenuSound(SoundEffectConstants.NAVIGATION_RIGHT)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (confirmMenuSelection()) playMenuSound(SoundEffectConstants.CLICK)
            }
        }
    }

    /** 在当前活动列内上下移动选择（不循环，卡在首尾）；返回选中项是否实际变化。 */
    private fun moveSelection(delta: Int): Boolean {
        if (activeColumn == COLUMN_CATEGORY) {
            val size = TvCatalog.categories.size
            val next = (categoryAdapter.selectedIndex + delta).coerceIn(0, size - 1)
            if (next == categoryAdapter.selectedIndex) return false
            categoryAdapter.setSelected(next)
            binding.categoryList.scrollToPosition(next)
            // 左列移动即预览：右列实时换成该分类的频道（默认选第一个），但不加载、不切台
            previewCategory(next)
        } else {
            val size = TvCatalog.categories[menuCategoryIndex].channels.size
            val next = (channelAdapter.selectedIndex + delta).coerceIn(0, size - 1)
            if (next == channelAdapter.selectedIndex) return false
            channelAdapter.setSelected(next)
            binding.channelList.scrollToPosition(next)
        }
        return true
    }

    /** 切换活动列（左/右），刷新两列高亮；返回活动列是否实际变化。 */
    private fun focusColumn(column: Int): Boolean {
        if (activeColumn == column) return false
        activeColumn = column
        syncColumnActive()
        return true
    }

    /** 左列移动时把右列换成对应分类的频道预览（选中第一个），不影响正在播放的画面。 */
    private fun previewCategory(categoryIndex: Int) {
        menuCategoryIndex = categoryIndex
        channelAdapter.submit(
            TvCatalog.categories[categoryIndex].channels.map { it.name },
            keepIndex = 0,
        )
        binding.channelList.scrollToPosition(0)
    }

    /** OK：在分类列则跳到频道列；在频道列则选中并换台。 */
    private fun confirmMenuSelection(): Boolean {
        if (activeColumn == COLUMN_CATEGORY) {
            focusColumn(COLUMN_CHANNEL)
        } else {
            onChannelChosen(channelAdapter.selectedIndex)
        }
        return true
    }

    /** 播放设备系统提供的菜单操作音，并自动遵循系统的按键音效设置。 */
    private fun playMenuSound(soundConstant: Int) {
        binding.menuPanel.playSoundEffect(soundConstant)
    }

    /** 触屏点击分类：切换预览分类并把焦点移到频道列。 */
    private fun onCategoryChosen(position: Int) {
        schedulePanelAutoClose()
        categoryAdapter.setSelected(position)
        previewCategory(position)
        focusColumn(COLUMN_CHANNEL)
    }

    /** 选定某个频道：换算成一维下标、关闭菜单并加载。 */
    private fun onChannelChosen(position: Int) {
        cancelPanelAutoClose()
        cancelPendingSingleTap()
        val flatIndex = TvCatalog.flatIndexOf(menuCategoryIndex, position)
        closeMenu()
        if (flatIndex != currentChannelIndex) {
            currentChannelIndex = flatIndex
            loadCurrentChannel()
        }
    }

    /** 依据 activeColumn 刷新两列高亮：活动列高亮蓝、非活动列暗选中态。 */
    private fun syncColumnActive() {
        categoryAdapter.setColumnActive(activeColumn == COLUMN_CATEGORY)
        channelAdapter.setColumnActive(activeColumn == COLUMN_CHANNEL)
    }
    // endregion

    // region 右侧系统设置面板
    private fun setupSettings() {
        if (settingsInitialized) return
        settingsInitialized = true
        settingsCategoryAdapter = MenuAdapter(R.layout.item_category) {
            focusSettingsColumn(COLUMN_SETTING_VALUE)
        }
        settingsValueAdapter = MenuAdapter(R.layout.item_channel) { position ->
            selectVideoEnhancement(position)
        }
        binding.settingsCategoryList.layoutManager = LinearLayoutManager(this)
        binding.settingsCategoryList.adapter = settingsCategoryAdapter
        binding.settingsCategoryList.itemAnimator = null
        binding.settingsValueList.layoutManager = LinearLayoutManager(this)
        binding.settingsValueList.adapter = settingsValueAdapter
        binding.settingsValueList.itemAnimator = null
        settingsCategoryAdapter.submit(
            listOf(getString(R.string.setting_video_enhancement)),
            keepIndex = 0,
        )
    }

    /** MENU 键呼出：定位到当前已生效档位，视频继续在面板后方播放。 */
    private fun openSettings() {
        if (settingsVisible) return
        cancelPendingSingleTap()
        hideTouchControls()
        hideTouchAdjustment()
        closeMenu()
        setupSettings()
        settingsVisible = true
        settingsActiveColumn = COLUMN_SETTING_VALUE
        val selected = VideoEnhancement.entries.indexOf(videoEnhancement)
        settingsValueAdapter.submit(videoEnhancementLabels(), keepIndex = selected)
        syncSettingsColumnActive()
        binding.settingsPanel.visibility = View.VISIBLE
        binding.settingsValueList.scrollToPosition(selected)
        schedulePanelAutoClose()
    }

    private fun closeSettings() {
        if (!settingsVisible) return
        settingsVisible = false
        binding.settingsPanel.visibility = View.GONE
        if (!menuVisible) cancelPanelAutoClose()
    }

    private fun handleSettingsKeyDown(keyCode: Int) {
        schedulePanelAutoClose()
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU -> closeSettings()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (moveSettingsSelection(-1)) playSettingsSound(SoundEffectConstants.NAVIGATION_UP)
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (moveSettingsSelection(+1)) {
                    playSettingsSound(SoundEffectConstants.NAVIGATION_DOWN)
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focusSettingsColumn(COLUMN_SETTING_VALUE)) {
                    playSettingsSound(SoundEffectConstants.NAVIGATION_LEFT)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusSettingsColumn(COLUMN_SETTING_CATEGORY)) {
                    playSettingsSound(SoundEffectConstants.NAVIGATION_RIGHT)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (settingsActiveColumn == COLUMN_SETTING_CATEGORY) {
                    focusSettingsColumn(COLUMN_SETTING_VALUE)
                } else {
                    selectVideoEnhancement(settingsValueAdapter.selectedIndex)
                }
                playSettingsSound(SoundEffectConstants.CLICK)
            }
        }
    }

    private fun moveSettingsSelection(delta: Int): Boolean {
        if (settingsActiveColumn == COLUMN_SETTING_CATEGORY) return false
        val next = (settingsValueAdapter.selectedIndex + delta)
            .coerceIn(0, VideoEnhancement.entries.lastIndex)
        if (next == settingsValueAdapter.selectedIndex) return false
        settingsValueAdapter.setSelected(next)
        binding.settingsValueList.scrollToPosition(next)
        return true
    }

    private fun focusSettingsColumn(column: Int): Boolean {
        if (settingsActiveColumn == column) return false
        settingsActiveColumn = column
        syncSettingsColumnActive()
        return true
    }

    private fun syncSettingsColumnActive() {
        settingsCategoryAdapter.setColumnActive(settingsActiveColumn == COLUMN_SETTING_CATEGORY)
        settingsValueAdapter.setColumnActive(settingsActiveColumn == COLUMN_SETTING_VALUE)
    }

    private fun selectVideoEnhancement(position: Int) {
        schedulePanelAutoClose()
        cancelPendingSingleTap()
        val selected = VideoEnhancement.entries.getOrNull(position) ?: return
        videoEnhancement = selected
        prefs.edit().putString(KEY_VIDEO_ENHANCEMENT, selected.wireValue).apply()
        settingsValueAdapter.submit(videoEnhancementLabels(), keepIndex = position)
        settingsValueAdapter.setColumnActive(settingsActiveColumn == COLUMN_SETTING_VALUE)
        browserEngine.applyVideoEnhancement(selected)
    }

    private fun videoEnhancementLabels(): List<String> = VideoEnhancement.entries.map { level ->
        getString(level.labelRes) + if (level == videoEnhancement) "  ✓" else ""
    }

    private fun playSettingsSound(soundConstant: Int) {
        binding.settingsPanel.playSoundEffect(soundConstant)
    }
    // endregion

    // region 生命周期
    override fun onPause() {
        super.onPause()
        if (::browserEngine.isInitialized) browserEngine.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::browserEngine.isInitialized) browserEngine.onResume()
        enableImmersiveFullscreen()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideChannelNameRunnable)
        uiHandler.removeCallbacks(hideTouchAdjustmentRunnable)
        uiHandler.removeCallbacks(hideTouchControlsRunnable)
        uiHandler.removeCallbacks(autoClosePanelRunnable)
        uiHandler.removeCallbacks(singleTapRunnable)
        uiHandler.removeCallbacks(loadChannelRunnable)
        cancelPlaybackAttempt()
        if (::browserEngine.isInitialized) browserEngine.destroy()
        super.onDestroy()
    }
    // endregion
}
