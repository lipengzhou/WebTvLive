package com.lipengzhou.webtvlive

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lipengzhou.webtvlive.databinding.ActivityMainBinding

/**
 * WebTvLive：
 *  - 启动后自动加载频道列表中的默认频道；
 *  - 页面加载完注入全屏脚本，把网页 <video> 铺满整个屏幕；
 *  - 遥控器方向键「上/下」循环换台，「确定」键呼出侧边频道菜单，返回键按两次退出应用。
 */
// TV 遥控器的 BACK 必须与方向键一起在 dispatchKeyEvent 中同步处理；
// predictive-back 回调无法替代实体遥控器按键的双击退出与菜单关闭语义。
@SuppressLint("GestureBackNavigation")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var browserEngine: BrowserEngine
    private var activityStartedAt = 0L
    private lateinit var playbackCoordinator: PlaybackCoordinator
    private var scheduledPlaybackAttemptId: Long? = null
    private var browserEngineGeneration = 0L
    private var browserRecoveryCount = 0
    private var browserFailureDialog: AlertDialog? = null
    private var updateController: AppUpdateController? = null
    // 返回键两次退出
    private var lastBackPressedTime = 0L

    // 记住「上次播放的频道」：应用退出后重开继续播放该台
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // 当前频道下标（遥控器上/下切换）；无记录时默认 CCTV-13 新闻，保持与旧版一致。
    // 这里用的是「所有分类频道拉平后的一维下标」，见 TvCatalog.flatChannels。
    private val currentChannelIndex: Int
        get() = playbackCoordinator.currentChannelIndex

    // region 侧边菜单状态
    // 菜单是否展开。菜单只是盖在视频上的左侧浮层，展开期间不碰浏览器 View，视频照常播放。
    private val panelCoordinator = PanelCoordinator()
    private val menuVisible: Boolean
        get() = panelCoordinator.channelsVisible
    private lateinit var channelMenuController: ChannelMenuController
    // endregion

    // region 右侧系统设置面板状态
    private val settingsVisible: Boolean
        get() = panelCoordinator.settingsVisible
    private lateinit var settingsController: SettingsPanelController
    // endregion

    private lateinit var touchController: PlaybackTouchController

    // 主线程 Handler：控制频道名浮层自动隐藏、以及换台防抖
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideChannelNameRunnable = Runnable {
        binding.channelName.visibility = View.GONE
    }
    private val autoClosePanelRunnable = Runnable {
        channelMenuController.close()
        settingsController.close()
    }
    // 换台防抖：狂按遥控器时不每次都加载，停手后只对最终频道加载一次
    private val loadChannelRunnable = Runnable { loadCurrentChannel() }
    private val playbackTimeoutRunnable = Runnable {
        scheduledPlaybackAttemptId?.let(::handlePlaybackTimeout)
    }
    companion object {
        private const val BACK_EXIT_INTERVAL = 2000L
        private const val CHANNEL_NAME_SHOW_MS = 3000L
        private const val PANEL_AUTO_CLOSE_MS = 12_000L
        // 换台防抖：停止按键 600ms 后才真正加载，避免连续切台把每个中间台都请求一遍被 CCTV 限流
        private const val CHANNEL_SWITCH_DEBOUNCE_MS = 600L
        private const val MAX_BROWSER_RECOVERY_COUNT = 1
        private const val DEFAULT_CHANNEL_INDEX = 13
        private const val STABLE_FALLBACK_SITE_NAME = "CCTV9"
        private const val SECONDARY_FALLBACK_SITE_NAME = "CCTV10"
        // 记住上次频道用的 SharedPreferences
        private const val PREFS_NAME = "webtvlive_prefs"
        private const val KEY_LAST_CHANNEL = "last_channel_index"
        private const val KEY_LAST_SUCCESSFUL_CHANNEL = "last_successful_channel_index"
        private const val TAG = "WebTvLive"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        activityStartedAt = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        touchController = PlaybackTouchController(
            activity = this,
            binding = binding,
            panelsVisible = { menuVisible || settingsVisible },
            closePanels = {
                channelMenuController.close()
                settingsController.close()
            },
            onPanelInteraction = ::schedulePanelAutoClose,
            openChannels = { channelMenuController.open(currentChannelIndex) },
            openSettings = { settingsController.open() },
            switchChannel = ::switchChannel,
        )
        channelMenuController = ChannelMenuController(
            activity = this,
            binding = binding,
            panelCoordinator = panelCoordinator,
            beforeOpen = {
                touchController.prepareForPanel()
                settingsController.close()
            },
            onOpenSettings = { settingsController.open() },
            onInteraction = ::schedulePanelAutoClose,
            onClosed = {
                if (!settingsVisible) cancelPanelAutoClose()
                updateController?.onPanelsClosed()
            },
            onChannelChosen = { flatIndex ->
                cancelPanelAutoClose()
                if (flatIndex != currentChannelIndex) {
                    playbackCoordinator.selectChannel(flatIndex)
                    loadCurrentChannel()
                }
            },
        )
        settingsController = SettingsPanelController(
            activity = this,
            binding = binding,
            panelCoordinator = panelCoordinator,
            beforeOpen = {
                touchController.prepareForPanel()
                channelMenuController.close()
            },
            onInteraction = ::schedulePanelAutoClose,
            onClosed = {
                if (!menuVisible) cancelPanelAutoClose()
                updateController?.onPanelsClosed()
            },
            onVideoEnhancementChanged = { browserEngine.applyVideoEnhancement(it) },
            onManualUpdateCheck = {
                cancelPanelAutoClose()
                updateController?.performManualCheck()
            },
        )
        if (BuildConfig.APP_UPDATES_ENABLED) {
            updateController = AppUpdateController(
                activity = this,
                panelsVisible = { menuVisible || settingsVisible },
                onManualUpdateAvailable = settingsController::close,
                onManualCheckFinished = ::schedulePanelAutoClose,
            ).also(AppUpdateController::start)
        } else {
            Log.i(TAG, "App updates disabled for ${BuildConfig.BUILD_TYPE} build")
        }

        enableImmersiveFullscreen()
        keepScreenOn()

        val restoredChannelIndex = restoreLastSuccessfulChannelIndex()
        playbackCoordinator = PlaybackCoordinator(
            channelCount = TvCatalog.flatChannels.size,
            initialChannelIndex = restoredChannelIndex,
            stableFallbackIndex = TvCatalog.indexOfSiteName(STABLE_FALLBACK_SITE_NAME),
            secondaryFallbackIndex = TvCatalog.indexOfSiteName(SECONDARY_FALLBACK_SITE_NAME),
            clockMillis = SystemClock::elapsedRealtime,
        )
        Log.i(TAG, "StartupTiming: activity_ready elapsed=${startupElapsed()}ms")
        createAndAttachBrowserEngine()
    }

    // region 浏览器内核
    private fun createAndAttachBrowserEngine() {
        browserEngine = createBrowserEngine(this)
        val generation = ++browserEngineGeneration
        browserEngine.attach(
            binding.webContainer,
            BrowserEngine.Listener { event ->
                if (generation != browserEngineGeneration || isFinishing || isDestroyed) {
                    return@Listener
                }
                when (event) {
                    BrowserEngine.Event.Initialized -> {
                    Log.i(TAG, "Browser engine ready; elapsed=${startupElapsed()}ms")
                    browserEngine.applyVideoEnhancement(settingsController.videoEnhancement)
                    loadCurrentChannel()
                    }

                    is BrowserEngine.Event.PageStarted -> {
                    Log.i(TAG, "Browser page start: ${event.url}")
                    if (event.url.startsWith(TvCatalog.YANGSHIPIN_HOME_URL)) {
                        Log.i(TAG, "StartupTiming: page_started elapsed=${startupElapsed()}ms")
                    }
                    }

                    is BrowserEngine.Event.PageStopped -> {
                    Log.i(TAG, "Browser page stop: success=${event.success}")
                    }

                    is BrowserEngine.Event.PlaybackReady -> handlePlaybackReady(event.requestId)

                    is BrowserEngine.Event.ChannelSelected -> {
                        Log.i(TAG, "Yangshipin channel selected: ${event.channel}")
                    }

                    is BrowserEngine.Event.Diagnostic -> Log.i(TAG, event.message)

                    is BrowserEngine.Event.Failed -> handleBrowserFailure(event.failure)
                }
            },
        )
    }

    private fun handleBrowserFailure(failure: BrowserEngine.Failure) {
        Log.e(TAG, "Browser failure: kind=${failure.kind}, detail=${failure.detail}")
        if (failure.kind == BrowserEngine.FailureKind.PROTOCOL) return
        cancelPlaybackAttempt()
        binding.loadingText.visibility = View.VISIBLE
        if (failure.recoverable && browserRecoveryCount < MAX_BROWSER_RECOVERY_COUNT) {
            browserRecoveryCount += 1
            browserEngineGeneration += 1
            uiHandler.post(::recreateBrowserEngine)
        } else {
            showBrowserFailureDialog(failure.detail)
        }
    }

    private fun recreateBrowserEngine() {
        if (isFinishing || isDestroyed) return
        if (::browserEngine.isInitialized) browserEngine.destroy()
        binding.webContainer.removeAllViews()
        createAndAttachBrowserEngine()
    }

    private fun showBrowserFailureDialog(detail: String) {
        if (browserFailureDialog?.isShowing == true || isFinishing || isDestroyed) return
        browserFailureDialog = AlertDialog.Builder(this)
            .setTitle(R.string.browser_failure_title)
            .setMessage(getString(R.string.browser_failure_message, detail))
            .setNegativeButton(R.string.touch_action_exit) { _, _ -> finish() }
            .setPositiveButton(R.string.browser_failure_retry) { _, _ ->
                browserRecoveryCount = 0
                recreateBrowserEngine()
            }
            .setCancelable(false)
            .setOnDismissListener { browserFailureDialog = null }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                }
                dialog.show()
            }
    }

    private fun handlePlaybackReady(requestId: Long) {
        val ready = playbackCoordinator.onPlaybackReady(requestId)
        if (ready != null) {
            uiHandler.removeCallbacks(playbackTimeoutRunnable)
            scheduledPlaybackAttemptId = null
            browserRecoveryCount = 0
            saveSuccessfulChannelIndex(ready.channelIndex)
            binding.loadingText.visibility = View.GONE
            updateController?.startAutomaticCheck()
            Log.i(
                TAG,
                "Playback ready; loading overlay hidden: request=$requestId, " +
                    "channel=${TvCatalog.flatChannels[ready.channelIndex].siteName}",
            )
            Log.i(
                TAG,
                "StartupTiming: playback_ready total=${startupElapsed()}ms, " +
                    "attempt=${ready.elapsedMillis}ms, stage=${ready.stage}",
            )
        } else {
            Log.i(
                TAG,
                "Ignored stale playing message: request=$requestId",
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
        if (hasFocus) {
            enableImmersiveFullscreen()
            updateController?.onWindowFocusChanged()
        }
    }
    // endregion

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!::touchController.isInitialized || !touchController.intercept(event)) {
            return super.dispatchTouchEvent(event)
        }
        return true
    }

    private fun schedulePanelAutoClose() {
        if (!menuVisible && !settingsVisible) return
        uiHandler.removeCallbacks(autoClosePanelRunnable)
        uiHandler.postDelayed(autoClosePanelRunnable, PANEL_AUTO_CLOSE_MS)
    }

    private fun cancelPanelAutoClose() {
        uiHandler.removeCallbacks(autoClosePanelRunnable)
    }


    // region 遥控器按键：菜单开合 / 上下换台 / 返回退出
    //
    // 关键：必须在 dispatchKeyEvent 里拦截，而不是 onKeyDown。
    // onKeyDown 只是「焦点浏览器 View 没消费按键时」才回调的兜底；方向键会先进网页：
    //  - 左/右让网页滚动或移动焦点 —— 表现为「视频画面移动」；
    //  - 焦点一旦进了网页，后续上/下也可能被网页吃掉 —— 表现为「换台失灵」。
    // 在 dispatchKeyEvent 提前吞掉这些键，浏览器永远拿不到，两个问题一并解决。
    // 菜单展开时，同一批方向键改为在菜单内导航（此时浏览器 View 仍在后面正常播放）。
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (updateController?.isDialogShowing == true) {
            return super.dispatchKeyEvent(event)
        }
        val keyCode = event.keyCode
        if (isRemoteControlKey(keyCode)) {
            // 只在按下时执行动作；抬起事件也一并吞掉，避免只截按下、抬起漏给 WebView
            if (event.action == KeyEvent.ACTION_DOWN) {
                when {
                    settingsVisible -> settingsController.handleKeyDown(keyCode)
                    menuVisible -> channelMenuController.handleKeyDown(keyCode)
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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                switchChannel(remoteChannelDelta(+1))
            }
            // 下：上一个频道（cctv2 -> cctv1 …，到开头循环回最后一个）
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                switchChannel(remoteChannelDelta(-1))
            }
            // OK/中央键：呼出侧边频道菜单
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                channelMenuController.open(currentChannelIndex)
            // MENU/设置键：从右侧呼出系统设置面板
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU -> settingsController.open()
            // 左/右：本 App 不做网页内导航，吞掉即可，防止网页滚动 / 移动焦点
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { /* no-op：故意屏蔽 */ }
        }
    }

    private fun remoteChannelDelta(defaultDelta: Int): Int =
        if (settingsController.channelSwitchReversed) -defaultDelta else defaultDelta

    /** 按 delta（+1/-1）循环切换频道。只更新下标 + 浮层反馈，真正加载走防抖，避免狂按时逐台请求被限流。 */
    private fun switchChannel(delta: Int) {
        val channelIndex = playbackCoordinator.moveSelection(delta)
        val channel = TvCatalog.flatChannels[channelIndex]
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
        uiHandler.removeCallbacks(playbackTimeoutRunnable)
        val channel = TvCatalog.flatChannels[currentChannelIndex]
        showChannelName(channel.name)
        executePlaybackAttempt(playbackCoordinator.start(browserEngine.canSwitchInPage()))
    }

    private fun executePlaybackAttempt(attempt: PlaybackCoordinator.Attempt) {
        uiHandler.removeCallbacks(playbackTimeoutRunnable)
        scheduledPlaybackAttemptId = attempt.attemptId
        uiHandler.postDelayed(playbackTimeoutRunnable, attempt.timeoutMs)
        binding.loadingText.visibility = View.VISIBLE
        val channel = TvCatalog.flatChannels[attempt.channelIndex]
        when (attempt.mode) {
            PlaybackCoordinator.LoadMode.IN_PAGE -> {
                if (!browserEngine.switchChannel(channel, attempt.requestId)) {
                    playbackCoordinator.onInPageCommandRejected(attempt.attemptId)?.let {
                        executePlaybackAttempt(it)
                    }
                    return
                }
                Log.i(
                    TAG,
                    "Requested in-page channel switch: ${channel.siteName}, " +
                        "request=${attempt.requestId}, stage=${attempt.stage}",
                )
            }
            PlaybackCoordinator.LoadMode.DIRECT -> {
                browserEngine.loadChannel(channel, attempt.requestId)
                Log.i(
                    TAG,
                    "Direct channel page load: ${channel.siteName}, pid=${channel.pid}, " +
                        "stage=${attempt.stage}, attempt=${attempt.attemptId}",
                )
            }
        }
    }

    private fun cancelPlaybackAttempt() {
        uiHandler.removeCallbacks(playbackTimeoutRunnable)
        scheduledPlaybackAttemptId = null
        if (::playbackCoordinator.isInitialized) playbackCoordinator.cancel()
    }

    private fun handlePlaybackTimeout(attemptId: Long) {
        scheduledPlaybackAttemptId = null
        val result = playbackCoordinator.onTimeout(attemptId)
        Log.w(
            TAG,
            "Playback timeout: attempt=$attemptId, result=$result",
        )
        when (result) {
            is PlaybackCoordinator.TimeoutResult.Retry -> {
                if (result.attempt.stage == PlaybackCoordinator.Stage.DIRECT_RETRY) {
                    Toast.makeText(this, R.string.channel_timeout_retry, Toast.LENGTH_SHORT).show()
                }
                executePlaybackAttempt(result.attempt)
            }
            is PlaybackCoordinator.TimeoutResult.Fallback -> {
                val fallback = TvCatalog.flatChannels[result.attempt.channelIndex]
                    Toast.makeText(
                        this,
                        getString(R.string.channel_timeout_fallback, fallback.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    showChannelName(fallback.name)
                executePlaybackAttempt(result.attempt)
            }
            PlaybackCoordinator.TimeoutResult.Exhausted -> stopAutomaticRecovery()
            PlaybackCoordinator.TimeoutResult.Stale -> Unit
        }
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

    // region 生命周期
    override fun onPause() {
        super.onPause()
        if (::browserEngine.isInitialized) browserEngine.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::browserEngine.isInitialized) browserEngine.onResume()
        enableImmersiveFullscreen()
        updateController?.onResume()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideChannelNameRunnable)
        uiHandler.removeCallbacks(autoClosePanelRunnable)
        uiHandler.removeCallbacks(loadChannelRunnable)
        cancelPlaybackAttempt()
        browserFailureDialog?.dismiss()
        updateController?.close()
        channelMenuController.dispose()
        touchController.dispose()
        if (::browserEngine.isInitialized) browserEngine.destroy()
        super.onDestroy()
    }
    // endregion
}
