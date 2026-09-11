package com.lipengzhou.webtvlive

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import com.lipengzhou.webtvlive.databinding.ActivityMainBinding

/**
 * Owns the whole playback session: browser-kernel lifecycle, the channel switch/retry state machine
 * ([PlaybackCoordinator]), the loading overlay, the channel-name overlay, timeout feedback and
 * last-channel persistence.
 *
 * [MainActivity] keeps only input routing and the side panels; it drives this controller via
 * [switchChannel] / [selectChannel] / [applyVideoEnhancement] and forwards the Activity lifecycle.
 * Kernel and playback state never leaks back into the Activity.
 */
class PlaybackController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val videoEnhancement: () -> VideoEnhancement,
    private val onPlaybackStarted: () -> Unit,
) {
    private lateinit var browserEngine: BrowserEngine
    private var browserEngineGeneration = 0L
    private var browserRecoveryCount = 0
    private var browserFailureDialog: AlertDialog? = null

    private lateinit var playbackCoordinator: PlaybackCoordinator
    private var scheduledPlaybackAttemptId: Long? = null
    private var activityStartedAt = 0L

    // 记住「上次播放的频道」：应用退出后重开继续播放该台
    private val prefs by lazy { AppPreferences.of(activity) }

    // 当前频道下标（遥控器上/下切换）；无记录时默认 CCTV-13 新闻，保持与旧版一致。
    // 这里用的是「所有分类频道拉平后的一维下标」，见 TvCatalog.flatChannels。
    val currentChannelIndex: Int
        get() = playbackCoordinator.currentChannelIndex

    private val handler = Handler(Looper.getMainLooper())
    private val hideChannelNameRunnable = Runnable {
        binding.channelName.visibility = View.GONE
    }
    // 换台防抖：狂按遥控器时不每次都加载，停手后只对最终频道加载一次
    private val loadChannelRunnable = Runnable { loadCurrentChannel() }
    private val playbackTimeoutRunnable = Runnable {
        scheduledPlaybackAttemptId?.let(::handlePlaybackTimeout)
    }

    /** 构建换台状态机并创建首个内核；[startedAt] 是 Activity 创建时刻，用于启动耗时统计。 */
    fun start(startedAt: Long) {
        activityStartedAt = startedAt
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

    /** 按 delta（+1/-1）循环切换频道。只更新下标 + 浮层反馈，真正加载走防抖，避免狂按时逐台请求被限流。 */
    fun switchChannel(delta: Int) {
        val channelIndex = playbackCoordinator.moveSelection(delta)
        val channel = TvCatalog.flatChannels[channelIndex]
        // 连按浏览期间保留当前台画面、只滚动更新浮层；不马上盖遮罩/loadUrl。
        // 遮罩留到真正加载时（loadCurrentChannel）再显示——那时旧 video 已被销毁，遮罩才盖得住。
        showChannelName(channel.name)
        // 防抖：停手 CHANNEL_SWITCH_DEBOUNCE_MS 后只加载最终停留的这一台
        handler.removeCallbacks(loadChannelRunnable)
        handler.postDelayed(loadChannelRunnable, CHANNEL_SWITCH_DEBOUNCE_MS)
    }

    /** 侧边菜单选台：与当前台不同才真正切换加载。 */
    fun selectChannel(flatIndex: Int) {
        if (flatIndex != currentChannelIndex) {
            playbackCoordinator.selectChannel(flatIndex)
            loadCurrentChannel()
        }
    }

    fun applyVideoEnhancement(level: VideoEnhancement) {
        if (::browserEngine.isInitialized) browserEngine.applyVideoEnhancement(level)
    }

    fun onResume() {
        if (::browserEngine.isInitialized) browserEngine.onResume()
    }

    fun onPause() {
        if (::browserEngine.isInitialized) browserEngine.onPause()
    }

    fun onDestroy() {
        handler.removeCallbacks(hideChannelNameRunnable)
        handler.removeCallbacks(loadChannelRunnable)
        cancelPlaybackAttempt()
        browserFailureDialog?.dismiss()
        if (::browserEngine.isInitialized) browserEngine.destroy()
    }

    // region 浏览器内核
    private fun createAndAttachBrowserEngine() {
        browserEngine = createBrowserEngine(activity)
        val generation = ++browserEngineGeneration
        browserEngine.attach(
            binding.webContainer,
            BrowserEngine.Listener { event ->
                if (generation != browserEngineGeneration ||
                    activity.isFinishing || activity.isDestroyed
                ) {
                    return@Listener
                }
                when (event) {
                    BrowserEngine.Event.Initialized -> {
                        Log.i(TAG, "Browser engine ready; elapsed=${startupElapsed()}ms")
                        browserEngine.applyVideoEnhancement(videoEnhancement())
                        loadCurrentChannel()
                    }

                    is BrowserEngine.Event.PageStarted -> {
                        Log.i(TAG, "Browser page start: ${event.url}")
                        if (event.url.startsWith(TvCatalog.YANGSHIPIN_HOME_URL)) {
                            Log.i(TAG, "StartupTiming: page_started elapsed=${startupElapsed()}ms")
                        }
                    }

                    is BrowserEngine.Event.PageStopped ->
                        Log.i(TAG, "Browser page stop: success=${event.success}")

                    is BrowserEngine.Event.PlaybackReady -> handlePlaybackReady(event.requestId)

                    is BrowserEngine.Event.ChannelSelected ->
                        Log.i(TAG, "Yangshipin channel selected: ${event.channel}")

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
            handler.post(::recreateBrowserEngine)
        } else {
            showBrowserFailureDialog(failure.detail)
        }
    }

    private fun recreateBrowserEngine() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (::browserEngine.isInitialized) browserEngine.destroy()
        binding.webContainer.removeAllViews()
        createAndAttachBrowserEngine()
    }

    private fun showBrowserFailureDialog(detail: String) {
        if (browserFailureDialog?.isShowing == true || activity.isFinishing || activity.isDestroyed) {
            return
        }
        browserFailureDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.browser_failure_title)
            .setMessage(activity.getString(R.string.browser_failure_message, detail))
            .setNegativeButton(R.string.touch_action_exit) { _, _ -> activity.finish() }
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
            handler.removeCallbacks(playbackTimeoutRunnable)
            scheduledPlaybackAttemptId = null
            browserRecoveryCount = 0
            saveSuccessfulChannelIndex(ready.channelIndex)
            binding.loadingText.visibility = View.GONE
            onPlaybackStarted()
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

    // region 换台加载 / 超时重试
    /**
     * 切换当前频道。页面适配脚本可用时优先在站内点击频道项；不可用时整页直达 pid 页面。
     */
    private fun loadCurrentChannel() {
        // 首次加载可能绕过防抖直接进来，这里清一次待执行的防抖任务，避免重复加载
        handler.removeCallbacks(loadChannelRunnable)
        handler.removeCallbacks(playbackTimeoutRunnable)
        val channel = TvCatalog.flatChannels[currentChannelIndex]
        showChannelName(channel.name)
        executePlaybackAttempt(playbackCoordinator.start(browserEngine.canSwitchInPage()))
    }

    private fun executePlaybackAttempt(attempt: PlaybackCoordinator.Attempt) {
        handler.removeCallbacks(playbackTimeoutRunnable)
        scheduledPlaybackAttemptId = attempt.attemptId
        handler.postDelayed(playbackTimeoutRunnable, attempt.timeoutMs)
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
        handler.removeCallbacks(playbackTimeoutRunnable)
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
                    Toast.makeText(activity, R.string.channel_timeout_retry, Toast.LENGTH_SHORT).show()
                }
                executePlaybackAttempt(result.attempt)
            }
            is PlaybackCoordinator.TimeoutResult.Fallback -> {
                val fallback = TvCatalog.flatChannels[result.attempt.channelIndex]
                Toast.makeText(
                    activity,
                    activity.getString(R.string.channel_timeout_fallback, fallback.name),
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
        Toast.makeText(activity, R.string.channel_timeout_manual, Toast.LENGTH_LONG).show()
        Log.e(TAG, "Automatic playback recovery exhausted; waiting for manual channel switch")
    }
    // endregion

    // region 频道持久化 / 浮层
    /** 读取最近一次真正收到 playing 的频道；兼容旧版本保存的频道下标。 */
    private fun restoreLastSuccessfulChannelIndex(): Int {
        val saved = if (prefs.contains(AppPreferences.KEY_LAST_SUCCESSFUL_CHANNEL)) {
            prefs.getInt(AppPreferences.KEY_LAST_SUCCESSFUL_CHANNEL, DEFAULT_CHANNEL_INDEX)
        } else {
            prefs.getInt(AppPreferences.KEY_LAST_CHANNEL, DEFAULT_CHANNEL_INDEX)
        }
        return if (saved in TvCatalog.flatChannels.indices) saved else DEFAULT_CHANNEL_INDEX
    }

    /** 只在目标频道真正出画面后持久化，避免失败频道污染下一次冷启动。 */
    private fun saveSuccessfulChannelIndex(index: Int) {
        prefs.edit {
            putInt(AppPreferences.KEY_LAST_SUCCESSFUL_CHANNEL, index)
            putInt(AppPreferences.KEY_LAST_CHANNEL, index)
        }
    }

    private fun startupElapsed(): Long = SystemClock.elapsedRealtime() - activityStartedAt

    /** 在屏幕角落短暂显示频道名，便于确认当前台。 */
    private fun showChannelName(name: String) {
        binding.channelName.text = name
        binding.channelName.visibility = View.VISIBLE
        handler.removeCallbacks(hideChannelNameRunnable)
        handler.postDelayed(hideChannelNameRunnable, CHANNEL_NAME_SHOW_MS)
    }
    // endregion

    private companion object {
        const val TAG = "WebTvLive"
        const val CHANNEL_NAME_SHOW_MS = 3000L
        // 换台防抖：停止按键 600ms 后才真正加载，避免连续切台把每个中间台都请求一遍被 CCTV 限流
        const val CHANNEL_SWITCH_DEBOUNCE_MS = 600L
        const val MAX_BROWSER_RECOVERY_COUNT = 1
        const val DEFAULT_CHANNEL_INDEX = 13
        const val STABLE_FALLBACK_SITE_NAME = "CCTV9"
        const val SECONDARY_FALLBACK_SITE_NAME = "CCTV10"
    }
}
