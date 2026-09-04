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
import com.lipengzhou.webtvlive.databinding.ActivityMainBinding

/**
 * WebTvLive：
 *  - 启动后自动加载频道列表中的默认频道；
 *  - 页面加载完注入全屏脚本，把网页 <video> 铺满整个屏幕；
 *  - 遥控器方向键「上/下」循环换台，返回键按两次退出应用。
 *
 * 后续（频道列表、多源、遥控器完整映射）按 docs/开发计划.md 推进。
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

    // 当前频道下标（遥控器上/下切换）；无记录时默认 CCTV-13 新闻，保持与旧版一致
    private var currentChannelIndex = 13

    // 主线程 Handler：控制频道名浮层自动隐藏
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideChannelNameRunnable = Runnable {
        binding.channelName.visibility = View.GONE
    }

    /** 一个 CCTV 频道：显示名 + 官网直播页 URL。 */
    private data class Channel(val name: String, val url: String)

    companion object {
        private const val BACK_EXIT_INTERVAL = 2000L
        private const val CHANNEL_NAME_SHOW_MS = 3000L
        // 记住上次频道用的 SharedPreferences
        private const val PREFS_NAME = "webtvlive_prefs"
        private const val KEY_LAST_CHANNEL = "last_channel_index"
        // 桌面 UA：与用 chrome-devtools 实测一致的页面结构（拿到标准 H5 <video> 播放器）
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // CCTV 官网直播频道表（顺序即遥控器上/下切换顺序，循环）
        private val CHANNELS = listOf(
            Channel("CCTV-1 综合", "https://tv.cctv.com/live/cctv1/"),
            Channel("CCTV-2 财经", "https://tv.cctv.com/live/cctv2/"),
            Channel("CCTV-3 综艺", "https://tv.cctv.com/live/cctv3/"),
            Channel("CCTV-4 中文国际", "https://tv.cctv.com/live/cctv4/"),
            Channel("CCTV-5 体育", "https://tv.cctv.com/live/cctv5/"),
            Channel("CCTV-5+ 体育赛事", "https://tv.cctv.com/live/cctv5plus/"),
            Channel("CCTV-6 电影", "https://tv.cctv.com/live/cctv6/"),
            Channel("CCTV-7 国防军事", "https://tv.cctv.com/live/cctv7/"),
            Channel("CCTV-8 电视剧", "https://tv.cctv.com/live/cctv8/"),
            Channel("CCTV-9 纪录", "https://tv.cctv.com/live/cctvjilu/"),
            Channel("CCTV-10 科教", "https://tv.cctv.com/live/cctv10/"),
            Channel("CCTV-11 戏曲", "https://tv.cctv.com/live/cctv11/"),
            Channel("CCTV-12 社会与法", "https://tv.cctv.com/live/cctv12/"),
            Channel("CCTV-13 新闻", "https://tv.cctv.com/live/cctv13/"),
            Channel("CCTV-14 少儿", "https://tv.cctv.com/live/cctvchild/"),
            Channel("CCTV-15 音乐", "https://tv.cctv.com/live/cctv15/"),
            Channel("CCTV-16 奥林匹克", "https://tv.cctv.com/live/cctv16/"),
            Channel("CCTV-17 农业农村", "https://tv.cctv.com/live/cctv17/"),
        )
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
                if (newProgress >= 100) {
                    binding.loadingText.visibility = View.GONE
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

    // region 遥控器按键：上/下换台，返回键两次退出
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                handleBack()
                return true
            }
            // 上：下一个频道（cctv1 -> cctv2 …，到末尾循环回第一个）
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                switchChannel(+1)
                return true
            }
            // 下：上一个频道（cctv2 -> cctv1 …，到开头循环回最后一个）
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                switchChannel(-1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 按 delta（+1/-1）循环切换频道并加载。 */
    private fun switchChannel(delta: Int) {
        val size = CHANNELS.size
        currentChannelIndex = ((currentChannelIndex + delta) % size + size) % size
        loadCurrentChannel()
    }

    /** 加载当前下标对应的频道，先重置 WebView 再载入，避免上一路视频残留。 */
    private fun loadCurrentChannel() {
        val channel = CHANNELS[currentChannelIndex]
        saveLastChannelIndex(currentChannelIndex)
        binding.loadingText.visibility = View.VISIBLE
        showChannelName(channel.name)
        webView.stopLoading()
        webView.loadUrl(channel.url)
    }

    /** 读取上次播放的频道下标；无记录或越界时回退到默认台。 */
    private fun restoreLastChannelIndex(): Int {
        val saved = prefs.getInt(KEY_LAST_CHANNEL, currentChannelIndex)
        return if (saved in CHANNELS.indices) saved else currentChannelIndex
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
