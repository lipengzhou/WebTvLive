package com.lipengzhou.webtvlive

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
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
 * WebTvLive 第一版：
 *  - 启动后自动加载固定直播页 CCTV-13；
 *  - 页面加载完注入全屏脚本，把网页 <video> 铺满整个屏幕；
 *  - 遥控器/系统「返回键」按两次退出应用。
 *
 * 后续（换台、频道列表、多源、遥控器完整映射）按 docs/开发计划.md 推进。
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

    companion object {
        private const val HOME_URL = "https://tv.cctv.com/live/cctv13/"
        private const val BACK_EXIT_INTERVAL = 2000L
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

        webView.loadUrl(HOME_URL)
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

    // region 返回键两次退出（遥控器返回键即 KEYCODE_BACK）
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
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
