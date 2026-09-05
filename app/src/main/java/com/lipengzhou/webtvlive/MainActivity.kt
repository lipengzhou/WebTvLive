package com.lipengzhou.webtvlive

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.net.http.SslError
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.lipengzhou.webtvlive.databinding.ActivityMainBinding

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
    private lateinit var webView: WebView

    // onShowCustomView 兜底用（部分站点走原生全屏时）
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // 注入脚本缓存
    private val injectJs: String by lazy { readAssetJs() }

    // 返回键两次退出
    private var lastBackPressedTime = 0L

    // 记住「上次播放的频道」：应用退出后重开继续播放该台
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // 当前频道下标（遥控器上/下切换）；无记录时默认 CCTV-13 新闻，保持与旧版一致。
    // 这里用的是「所有分类频道拉平后的一维下标」，见 TvCatalog.flatChannels。
    private var currentChannelIndex = 13

    // region 侧边菜单状态
    // 菜单是否展开。菜单只是盖在视频上的左侧浮层，展开期间不碰 WebView，视频照常播放。
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
        // 桌面 UA：与用 chrome-devtools 实测一致的页面结构（拿到标准 H5 <video> 播放器）
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableImmersiveFullscreen()
        keepScreenOn()

        webView = createWebView()
        binding.webContainer.addView(
            webView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        setupMenu()

        currentChannelIndex = restoreLastChannelIndex()
        loadCurrentChannel()
    }

    // region WebView 构建
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false   // 允许自动起播
            // 不开 useWideViewPort/loadWithOverviewMode：桌面 UA 下它们会把整页缩放，
            // 导致 100vw/100vh 与真实可视区不一致、视频铺不满。
            userAgentString = DESKTOP_UA
            // 允许 https 页面里加载 blob / 混合内容（直播流常见）
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
        }
        wv.setBackgroundColor(0xFF000000.toInt())

        wv.addJavascriptInterface(JsBridge(), "AndroidTV")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false   // 站内跳转都在本 WebView 内完成

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                inject()   // 双保险：进度 100 之外再注入一次
            }

            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: SslError?
            ) {
                // 直播源证书偶有问题，第一版放行以保证能播（后续可收紧）
                handler?.proceed()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // 页面加载完只补一次注入；加载遮罩不在这里隐藏——
                // 此刻网页往往还停在封面/播放按钮，得等视频真正开播（notifyVideoPlaying）再切走。
                if (newProgress >= 100) {
                    inject()
                }
            }

            // 站点若触发原生全屏（H5 requestFullscreen），用容器兜底承接
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                binding.fullscreenContainer.addView(
                    view,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.webContainer.visibility = View.GONE
                // 走原生全屏说明视频已在播放，撤掉加载遮罩
                binding.loadingText.visibility = View.GONE
                enableImmersiveFullscreen()
            }

            override fun onHideCustomView() {
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.visibility = View.GONE
                binding.webContainer.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                enableImmersiveFullscreen()
            }
        }
        return wv
    }

    private fun inject() {
        if (injectJs.isNotEmpty()) {
            webView.evaluateJavascript(injectJs, null)
        }
    }

    private fun readAssetJs(): String = try {
        assets.open("default_js_template.js").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        ""
    }
    // endregion

    // region JS -> Native 心跳
    private inner class JsBridge {
        @JavascriptInterface
        fun notifyVideoPlaying() {
            // 已在播放：隐藏加载提示（切回主线程）
            runOnUiThread { binding.loadingText.visibility = View.GONE }
        }

        @JavascriptInterface
        fun setVideoSize(width: Int, height: Int) {
            // 第一版暂不使用尺寸；预留给后续「画面比例」功能
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
    // onKeyDown 只是「焦点 View（WebView）没消费按键时」才回调的兜底；方向键会先进 WebView：
    //  - 左/右 让 WebView 滚动页面 / 把焦点移进网页 —— 表现为「视频画面移动」；
    //  - 焦点一旦进了网页，后续上/下也被 WebView 吃掉，传不到这里 —— 表现为「换台失灵」。
    // 在 dispatchKeyEvent 提前吞掉这些键，WebView 永远拿不到，两个问题一并解决。
    // 菜单展开时，同一批方向键改为在菜单内导航（此时 WebView 仍在后面正常播放）。
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
            // 左/右：本 App 不做网页内导航，吞掉即可，防止 WebView 滚动页面 / 移动焦点
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

    /** 加载当前下标对应的频道，先重置 WebView 再载入，避免上一路视频残留。 */
    private fun loadCurrentChannel() {
        // 首次加载可能绕过防抖直接进来，这里清一次待执行的防抖任务，避免重复加载
        uiHandler.removeCallbacks(loadChannelRunnable)
        val channel = TvCatalog.flatChannels[currentChannelIndex]
        saveLastChannelIndex(currentChannelIndex)
        binding.loadingText.visibility = View.VISIBLE
        showChannelName(channel.name)
        webView.stopLoading()
        webView.loadUrl(channel.url)
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
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter

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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> moveSelection(+1)
            KeyEvent.KEYCODE_DPAD_LEFT -> focusColumn(COLUMN_CATEGORY)
            KeyEvent.KEYCODE_DPAD_RIGHT -> focusColumn(COLUMN_CHANNEL)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> confirmMenuSelection()
        }
    }

    /** 在当前活动列内上下移动选择（不循环，卡在首尾）。 */
    private fun moveSelection(delta: Int) {
        if (activeColumn == COLUMN_CATEGORY) {
            val size = TvCatalog.categories.size
            val next = (categoryAdapter.selectedIndex + delta).coerceIn(0, size - 1)
            if (next == categoryAdapter.selectedIndex) return
            categoryAdapter.setSelected(next)
            binding.categoryList.smoothScrollToPosition(next)
            // 左列移动即预览：右列实时换成该分类的频道（默认选第一个），但不加载、不切台
            previewCategory(next)
        } else {
            val size = TvCatalog.categories[menuCategoryIndex].channels.size
            val next = (channelAdapter.selectedIndex + delta).coerceIn(0, size - 1)
            if (next == channelAdapter.selectedIndex) return
            channelAdapter.setSelected(next)
            binding.channelList.smoothScrollToPosition(next)
        }
    }

    /** 切换活动列（左/右），刷新两列高亮。 */
    private fun focusColumn(column: Int) {
        if (activeColumn == column) return
        activeColumn = column
        syncColumnActive()
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
    private fun confirmMenuSelection() {
        if (activeColumn == COLUMN_CATEGORY) {
            focusColumn(COLUMN_CHANNEL)
        } else {
            onChannelChosen(channelAdapter.selectedIndex)
        }
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
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        enableImmersiveFullscreen()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(hideChannelNameRunnable)
        uiHandler.removeCallbacks(loadChannelRunnable)
        binding.webContainer.removeAllViews()
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            removeJavascriptInterface("AndroidTV")
            destroy()
        }
        super.onDestroy()
    }
    // endregion
}
