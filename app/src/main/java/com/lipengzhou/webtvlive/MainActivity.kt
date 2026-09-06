package com.lipengzhou.webtvlive

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.lipengzhou.webtvlive.databinding.ActivityMainBinding
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * WebTvLive：
 *  - 启动后自动加载频道列表中的默认频道；
 *  - 页面加载完注入全屏脚本，把网页 <video> 铺满整个屏幕；
 *  - 遥控器方向键「上/下」循环换台，「确定」键呼出侧边频道菜单，返回键按两次退出应用。
 *
 * 后续（多源、遥控器完整映射）按 docs/开发计划.md 推进。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession? = null
    private var extensionPort: WebExtension.Port? = null
    private var pageLoadInProgress = false
    private var channelSwitchRequestId = 0L
    private var waitingPlaybackRequestId: Long? = null

    // 返回键两次退出
    private var lastBackPressedTime = 0L

    // 记住「上次播放的频道」：应用退出后重开继续播放该台
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // 当前频道下标（遥控器上/下切换）；无记录时默认 CCTV-13 新闻，保持与旧版一致。
    // 这里用的是「所有分类频道拉平后的一维下标」，见 TvCatalog.flatChannels。
    private var currentChannelIndex = 13

    // region 侧边菜单状态
    // 菜单是否展开。菜单只是盖在视频上的左侧浮层，展开期间不碰 GeckoView，视频照常播放。
    private var menuVisible = false
    // 当前活动列：左=分类，右=频道。方向键上/下作用在活动列上，左/右在两列间切换。
    private var activeColumn = COLUMN_CHANNEL
    // 右栏当前展示的是哪个分类的频道
    private var menuCategoryIndex = 0

    private lateinit var categoryAdapter: MenuAdapter
    private lateinit var channelAdapter: MenuAdapter
    // endregion

    // 主线程 Handler：控制频道名浮层自动隐藏、以及换台防抖
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideChannelNameRunnable = Runnable {
        binding.channelName.visibility = View.GONE
    }
    // 换台防抖：狂按遥控器时不每次都加载，停手后只对最终频道加载一次
    private val loadChannelRunnable = Runnable { loadCurrentChannel() }

    companion object {
        private const val BACK_EXIT_INTERVAL = 2000L
        private const val CHANNEL_NAME_SHOW_MS = 3000L
        // 换台防抖：停止按键 600ms 后才真正加载，避免连续切台把每个中间台都请求一遍被 CCTV 限流
        private const val CHANNEL_SWITCH_DEBOUNCE_MS = 600L
        // 记住上次频道用的 SharedPreferences
        private const val PREFS_NAME = "webtvlive_prefs"
        private const val KEY_LAST_CHANNEL = "last_channel_index"
        // 侧边菜单两列
        private const val COLUMN_CATEGORY = 0
        private const val COLUMN_CHANNEL = 1
        private const val TAG = "WebTvLive"
        private const val EXTENSION_LOCATION = "resource://android/assets/webextension/"
        private const val EXTENSION_ID = "webtvlive@lipengzhou.com"
        private const val NATIVE_APP_ID = "webtvlive"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        @Volatile
        private var runtime: GeckoRuntime? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveFullscreen()
        keepScreenOn()

        setupMenu()

        currentChannelIndex = restoreLastChannelIndex()
        createAndAttachGeckoView()
    }

    // region GeckoView 浏览器内核
    private fun createAndAttachGeckoView() {
        val createdView = GeckoView(this)
        val session = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .userAgentOverride(DESKTOP_UA)
                // 保持桌面页面结构，但使用设备视口，避免 980px 桌面视口缩放后留下黑边。
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .allowJavascript(true)
                .suspendMediaWhenInactive(true)
                .build(),
        )
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                if (fullScreen) binding.loadingText.visibility = View.GONE
                enableImmersiveFullscreen()
            }

            override fun onCrash(session: GeckoSession) {
                Log.e(TAG, "GeckoView content process crashed")
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                pageLoadInProgress = true
                Log.i(TAG, "GeckoView page start: " + url)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                pageLoadInProgress = false
                Log.i(TAG, "GeckoView page stop: success=" + success)
            }
        }
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                permission: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                val allowed = permission.permission ==
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    permission.permission ==
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                return GeckoResult.fromValue(
                    if (allowed) {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    } else {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    },
                )
            }
        }

        val appRuntime = runtime ?: GeckoRuntime.create(
            applicationContext,
            GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .consoleOutput(true)
                // 电视内存有限；关闭站点隔离和独立扩展进程，减少跨域直播页的子进程数量。
                .fissionEnabled(false)
                .extensionsProcessEnabled(false)
                .build(),
        ).also { runtime = it }

        session.open(appRuntime)
        session.setActive(true)
        session.setFocused(true)
        createdView.setSession(session)
        geckoView = createdView
        geckoSession = session
        binding.webContainer.addView(
            createdView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        installWebExtensionAndLoad(appRuntime, session)
    }

    private fun installWebExtensionAndLoad(runtime: GeckoRuntime, session: GeckoSession) {
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { extension ->
                    if (isFinishing || isDestroyed) return@accept
                    if (extension == null) {
                        Log.e(TAG, "GeckoView extension install returned null")
                        loadCurrentChannel()
                        return@accept
                    }
                    session.webExtensionController.setMessageDelegate(
                        extension,
                        object : WebExtension.MessageDelegate {
                            override fun onConnect(port: WebExtension.Port) {
                                port.setDelegate(
                                    object : WebExtension.PortDelegate {
                                        override fun onPortMessage(
                                            message: Any,
                                            port: WebExtension.Port,
                                        ) {
                                            handleExtensionMessage(message, port)
                                        }

                                        override fun onDisconnect(port: WebExtension.Port) {
                                            runOnUiThread {
                                                if (extensionPort === port) extensionPort = null
                                            }
                                        }
                                    },
                                )
                                runOnUiThread {
                                    extensionPort = port
                                    Log.i(TAG, "WebExtension native port connected")
                                }
                            }

                            override fun onMessage(
                                nativeApp: String,
                                message: Any,
                                sender: WebExtension.MessageSender,
                            ): GeckoResult<Any>? {
                                handleExtensionMessage(message)
                                return null
                            }
                        },
                        NATIVE_APP_ID,
                    )
                    Log.i(TAG, "GeckoView ready; built-in extension installed")
                    loadCurrentChannel()
                },
                { error ->
                    if (isFinishing || isDestroyed) return@accept
                    Log.e(TAG, "Unable to install GeckoView extension", error)
                    loadCurrentChannel()
                },
            )
    }

    /** 统一处理一次性消息和持久 Port 消息。Port ready 后下发当前频道。 */
    private fun handleExtensionMessage(message: Any, port: WebExtension.Port? = null) {
        val payload = message as? JSONObject ?: return
        when (payload.optString("type")) {
            "ready" -> runOnUiThread {
                val activePort = port ?: extensionPort ?: return@runOnUiThread
                extensionPort = activePort
                sendChannelSwitch(activePort, TvCatalog.flatChannels[currentChannelIndex])
            }
            "playing" -> runOnUiThread {
                val requestId = payload.optLong("requestId", -1L)
                if (requestId == waitingPlaybackRequestId) {
                    waitingPlaybackRequestId = null
                    binding.loadingText.visibility = View.GONE
                    Log.i(TAG, "Playback ready; loading overlay hidden: request=$requestId")
                } else {
                    Log.i(
                        TAG,
                        "Ignored stale playing message: request=$requestId, " +
                            "waiting=$waitingPlaybackRequestId",
                    )
                }
            }
            "channelSelected" -> Log.i(
                TAG,
                "Yangshipin channel selected: ${payload.optString("channel")}",
            )
            "channelNotFound" -> Log.e(
                TAG,
                "Yangshipin channel not found: ${payload.optString("channel")}",
            )
            "diagnostic" -> Log.i(TAG, payload.optString("message"))
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

    // region 遥控器按键：菜单开合 / 上下换台 / 返回退出
    //
    // 关键：必须在 dispatchKeyEvent 里拦截，而不是 onKeyDown。
    // onKeyDown 只是「焦点 View（GeckoView）没消费按键时」才回调的兜底；方向键会先进网页：
    //  - 左/右让网页滚动或移动焦点 —— 表现为「视频画面移动」；
    //  - 焦点一旦进了网页，后续上/下也可能被网页吃掉 —— 表现为「换台失灵」。
    // 在 dispatchKeyEvent 提前吞掉这些键，浏览器永远拿不到，两个问题一并解决。
    // 菜单展开时，同一批方向键改为在菜单内导航（此时 GeckoView 仍在后面正常播放）。
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (isRemoteControlKey(keyCode)) {
            // 只在按下时执行动作；抬起事件也一并吞掉，避免只截按下、抬起漏给 WebView
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (menuVisible) handleMenuKeyDown(keyCode) else handleRemoteKeyDown(keyCode)
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
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
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
     * 切换当前频道。央视频首页只加载一次；WebExtension Port 可用后，后续切台仅点击站内频道项，
     * 由央视频局部替换播放器，不再 stop/loadUri 重载整页。
     */
    private fun loadCurrentChannel() {
        // 首次加载可能绕过防抖直接进来，这里清一次待执行的防抖任务，避免重复加载
        uiHandler.removeCallbacks(loadChannelRunnable)
        val channel = TvCatalog.flatChannels[currentChannelIndex]
        saveLastChannelIndex(currentChannelIndex)
        showChannelName(channel.name)

        val port = extensionPort
        if (port != null) {
            sendChannelSwitch(port, channel)
            return
        }

        // 首次启动或 Port 意外断开时才重新加载首页。正常换台不会走到这里。
        binding.loadingText.visibility = View.VISIBLE
        if (!pageLoadInProgress) {
            geckoSession?.stop()
            geckoSession?.loadUri(TvCatalog.YANGSHIPIN_HOME_URL)
        }
    }

    private fun sendChannelSwitch(port: WebExtension.Port, channel: Channel) {
        try {
            // 央视频页内换台时旧 video 会继续播放一小段时间。先盖住旧画面，直到扩展确认
            // 网站已换成新的 video 节点且新频道真正开始播放，再由 playing 消息移除遮罩。
            val requestId = ++channelSwitchRequestId
            waitingPlaybackRequestId = requestId
            binding.loadingText.visibility = View.VISIBLE
            port.postMessage(
                JSONObject()
                    .put("type", "switchChannel")
                    .put("channel", channel.siteName)
                    .put("requestId", requestId),
            )
            Log.i(
                TAG,
                "Requested in-page channel switch: ${channel.siteName}, request=$requestId",
            )
        } catch (error: Exception) {
            Log.e(TAG, "Unable to send channel switch through WebExtension port", error)
            if (extensionPort === port) extensionPort = null
            binding.loadingText.visibility = View.VISIBLE
            if (!pageLoadInProgress) {
                geckoSession?.stop()
                geckoSession?.loadUri(TvCatalog.YANGSHIPIN_HOME_URL)
            }
        }
    }

    /** 读取上次播放的频道下标；无记录或越界时回退到默认台。 */
    private fun restoreLastChannelIndex(): Int {
        val saved = prefs.getInt(KEY_LAST_CHANNEL, currentChannelIndex)
        return if (saved in TvCatalog.flatChannels.indices) saved else currentChannelIndex
    }

    /** 持久化当前频道下标，供下次启动恢复。 */
    private fun saveLastChannelIndex(index: Int) {
        prefs.edit().putInt(KEY_LAST_CHANNEL, index).apply()
    }

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
    }

    /** 关闭菜单。视频一直在后面播放，这里只是收起浮层。 */
    private fun closeMenu() {
        if (!menuVisible) return
        menuVisible = false
        binding.menuPanel.visibility = View.GONE
    }

    /** 菜单展开态下的按键：上下在活动列内移动，左右切列，OK 选中，返回关闭。 */
    private fun handleMenuKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> closeMenu()
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
        categoryAdapter.setSelected(position)
        previewCategory(position)
        focusColumn(COLUMN_CHANNEL)
    }

    /** 选定某个频道：换算成一维下标、关闭菜单并加载。 */
    private fun onChannelChosen(position: Int) {
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

    // region 生命周期
    override fun onPause() {
        super.onPause()
        geckoSession?.setFocused(false)
        geckoSession?.setActive(false)
    }

    override fun onResume() {
        super.onResume()
        geckoSession?.setActive(true)
        geckoSession?.setFocused(true)
        enableImmersiveFullscreen()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideChannelNameRunnable)
        uiHandler.removeCallbacks(loadChannelRunnable)
        extensionPort?.disconnect()
        extensionPort = null
        geckoView?.releaseSession()
        geckoSession?.close()
        binding.webContainer.removeAllViews()
        geckoView = null
        geckoSession = null
        super.onDestroy()
    }
    // endregion
}
