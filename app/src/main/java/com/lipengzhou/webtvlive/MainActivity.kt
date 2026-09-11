package com.lipengzhou.webtvlive

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
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
 *
 * 本类只保留「生命周期 + 顶层输入路由 + 面板协调」：内核承载、换台/超时/重试状态机、频道持久化
 * 都在 [PlaybackController]，菜单/设置/触屏/更新分别在各自控制器。改行为请下沉到对应控制器，
 * 不要把逻辑再堆回 Activity。
 */
// TV 遥控器的 BACK 必须与方向键一起在 dispatchKeyEvent 中同步处理；
// predictive-back 回调无法替代实体遥控器按键的双击退出与菜单关闭语义。
@SuppressLint("GestureBackNavigation")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var playbackController: PlaybackController
    private var updateController: AppUpdateController? = null
    // 返回键两次退出
    private var lastBackPressedTime = 0L

    private val currentChannelIndex: Int
        get() = playbackController.currentChannelIndex

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

    // 主线程 Handler：控制面板无操作自动关闭
    private val uiHandler = Handler(Looper.getMainLooper())
    private val autoClosePanelRunnable = Runnable {
        channelMenuController.close()
        settingsController.close()
    }

    companion object {
        private const val BACK_EXIT_INTERVAL = 2000L
        private const val PANEL_AUTO_CLOSE_MS = 12_000L
        private const val TAG = "WebTvLive"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val activityStartedAt = SystemClock.elapsedRealtime()
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
            switchChannel = { delta -> playbackController.switchChannel(delta) },
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
                playbackController.selectChannel(flatIndex)
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
            onVideoEnhancementChanged = { playbackController.applyVideoEnhancement(it) },
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

        playbackController = PlaybackController(
            activity = this,
            binding = binding,
            videoEnhancement = { settingsController.videoEnhancement },
            onPlaybackStarted = { updateController?.startAutomaticCheck() },
        )
        playbackController.start(activityStartedAt)
    }

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
                playbackController.switchChannel(remoteChannelDelta(+1))
            }
            // 下：上一个频道（cctv2 -> cctv1 …，到开头循环回最后一个）
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                playbackController.switchChannel(remoteChannelDelta(-1))
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
        playbackController.onPause()
    }

    override fun onResume() {
        super.onResume()
        playbackController.onResume()
        enableImmersiveFullscreen()
        updateController?.onResume()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(autoClosePanelRunnable)
        updateController?.close()
        channelMenuController.dispose()
        touchController.dispose()
        playbackController.onDestroy()
        super.onDestroy()
    }
    // endregion
}
