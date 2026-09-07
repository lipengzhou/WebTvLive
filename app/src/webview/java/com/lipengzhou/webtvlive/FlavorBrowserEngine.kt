package com.lipengzhou.webtvlive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class FlavorBrowserEngine(private val context: Context) : BrowserEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var listener: BrowserEngine.Listener? = null
    private var adapterScript: String? = null
    private var bridgeReady = false
    private var pendingChannelSwitch: PendingChannelSwitch? = null
    private var videoEnhancement = VideoEnhancement.ORIGINAL
    private val resourceFilterLock = Any()
    private val resourceFilterCounts = mutableMapOf(
        "catalogArtwork" to 0,
        "qrAndFooterArtwork" to 0,
        "routePrefetch" to 0,
    )
    private var resourceFilterTotal = 0

    private data class PendingChannelSwitch(
        val channel: Channel,
        val requestId: Long,
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(container: ViewGroup, listener: BrowserEngine.Listener) {
        this.listener = listener
        adapterScript = loadAsset("webextension/player_adapter.js")
        WebView.setWebContentsDebuggingEnabled(isDebuggable())

        val createdView = WebView(container.context)
        createdView.setBackgroundColor(android.graphics.Color.BLACK)
        createdView.addJavascriptInterface(NativeBridge(), BRIDGE_NAME)
        createdView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadsImagesAutomatically = true
            useWideViewPort = false
            loadWithOverviewMode = false
            userAgentString = DESKTOP_UA
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        createdView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.i(TAG, "WebView console: ${consoleMessage.message()}")
                return true
            }
        }
        createdView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                bridgeReady = false
                listener.onPageStarted(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                listener.onPageStopped(true)
                injectAdapter(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    listener.onPageStopped(false)
                    listener.onDiagnostic("WebView main-frame error: ${error.description}")
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val ruleId = blockedRuleId(request) ?: return null
                recordResourceBlock(ruleId)
                return emptyResponse()
            }
        }

        webView = createdView
        container.addView(
            createdView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        listener.onReady()
    }

    override fun canSwitchInPage(): Boolean = bridgeReady

    override fun loadChannel(channel: Channel, requestId: Long) {
        bridgeReady = false
        pendingChannelSwitch = PendingChannelSwitch(channel, requestId)
        webView?.loadUrl(TvCatalog.pageUrl(channel))
    }

    override fun switchChannel(channel: Channel, requestId: Long): Boolean {
        if (!bridgeReady) return false
        pendingChannelSwitch = PendingChannelSwitch(channel, requestId)
        postPendingChannelSwitch()
        return true
    }

    override fun applyVideoEnhancement(level: VideoEnhancement) {
        videoEnhancement = level
        postMessage(
            JSONObject()
                .put("type", "setVideoEnhancement")
                .put("level", level.wireValue),
        )
        Log.i(TAG, "Video enhancement requested: ${level.wireValue}")
    }

    override fun onResume() {
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        webView?.onPause()
        webView?.pauseTimers()
    }

    override fun destroy() {
        pendingChannelSwitch = null
        bridgeReady = false
        webView?.parent?.let { parent ->
            if (parent is ViewGroup) parent.removeView(webView)
        }
        webView?.removeJavascriptInterface(BRIDGE_NAME)
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        listener = null
    }

    private fun injectAdapter(view: WebView) {
        val script = adapterScript ?: return
        view.evaluateJavascript(WEBVIEW_RUNTIME_SHIM + "\n" + script, null)
    }

    private fun postPendingChannelSwitch() {
        val pending = pendingChannelSwitch ?: return
        postMessage(
            JSONObject()
                .put("type", "switchChannel")
                .put("channel", pending.channel.siteName)
                .put("pid", pending.channel.pid)
                .put("requestId", pending.requestId),
        )
        pendingChannelSwitch = null
    }

    private fun postMessage(message: JSONObject) {
        val target = webView ?: return
        val script = "window.__webTvLiveDispatchFromNative && " +
            "window.__webTvLiveDispatchFromNative(${JSONObject.quote(message.toString())});"
        target.evaluateJavascript(script, null)
    }

    private fun handleBridgeMessage(raw: String?) {
        val payload = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        when (payload.optString("type")) {
            "ready" -> {
                bridgeReady = true
                applyVideoEnhancement(videoEnhancement)
                postPendingChannelSwitch()
            }

            "playing" -> listener?.onPlaybackReady(payload.optLong("requestId", -1L))
            "channelSelected" -> listener?.onChannelSelected(payload.optString("channel"))
            "channelNotFound" -> listener?.onChannelNotFound(payload.optString("channel"))
            "diagnostic" -> {
                val message = payload.optString("message").ifBlank {
                    payload.optString("detail")
                }
                listener?.onDiagnostic(message)
            }
        }
    }

    private fun blockedRuleId(request: WebResourceRequest): String? {
        val url = request.url ?: return null
        val value = url.toString()
        if (request.isForMainFrame) return null
        val path = url.path.orEmpty()
        val host = url.host.orEmpty()
        return when {
            host == "resources.yangshipin.cn" &&
                path.startsWith("/assets/oms/image/") -> "catalogArtwork"

            host == "sapi.yangshipin.cn" &&
                (path.startsWith("/assets/2022/pcicon/qrcode/") ||
                    path == "/assets/2022/pcicon/0523/logo_bottom@2x.png" ||
                    path.startsWith("/assets/2022/pcicon/icon_bottom_")) -> "qrAndFooterArtwork"

            value.startsWith("https://www.yangshipin.cn/js/chunk-") &&
                request.headerValue("Purpose") == "prefetch" -> "routePrefetch"

            value.startsWith("https://www.yangshipin.cn/css/chunk-") &&
                request.headerValue("Purpose") == "prefetch" -> "routePrefetch"

            else -> null
        }
    }

    private fun WebResourceRequest.headerValue(name: String): String? =
        requestHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun recordResourceBlock(ruleId: String) {
        synchronized(resourceFilterLock) {
            resourceFilterTotal += 1
            resourceFilterCounts[ruleId] = (resourceFilterCounts[ruleId] ?: 0) + 1
        }
    }

    private fun resourceFilterStatsJson(): String = synchronized(resourceFilterLock) {
        JSONObject()
            .put("enabled", true)
            .put("total", resourceFilterTotal)
            .put(
                "counts",
                JSONObject().apply {
                    resourceFilterCounts.forEach { (key, value) -> put(key, value) }
                },
            )
            .toString()
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArray(0).inputStream(),
        )

    private fun loadAsset(path: String): String? {
        return runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.onFailure {
            Log.e(TAG, "Unable to load asset: $path", it)
        }.getOrNull()
    }

    private fun isDebuggable(): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private inner class NativeBridge {
        @JavascriptInterface
        fun postMessage(raw: String?) {
            mainHandler.post { handleBridgeMessage(raw) }
        }

        @JavascriptInterface
        fun getResourceFilterStats(): String = resourceFilterStatsJson()
    }

    companion object {
        private const val TAG = "WebTvLive"
        private const val BRIDGE_NAME = "WebTvLiveNative"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val WEBVIEW_RUNTIME_SHIM = """
(function () {
  if (window.__webTvLiveRuntimeReady) return;
  window.__webTvLiveRuntimeReady = true;
  var nativeListeners = [];
  var runtimeListeners = [];
  function post(payload) {
    try {
      window.WebTvLiveNative.postMessage(JSON.stringify(payload || {}));
    } catch (e) {}
  }
  window.__webTvLiveDispatchFromNative = function (raw) {
    var message = null;
    try { message = JSON.parse(raw); } catch (e) { return; }
    nativeListeners.slice().forEach(function (listener) {
      try { listener(message); } catch (e) {}
    });
  };
  window.browser = {
    runtime: {
      connectNative: function () {
        return {
          onMessage: {
            addListener: function (listener) { nativeListeners.push(listener); }
          },
          onDisconnect: { addListener: function () {} },
          postMessage: function (payload) { post(payload); }
        };
      },
      sendNativeMessage: function (app, payload) {
        var forwarded = payload || {};
        if (!forwarded.type && forwarded.message) forwarded.type = 'diagnostic';
        post(forwarded);
        return Promise.resolve();
      },
      sendMessage: function (message) {
        if (message && message.type === 'getResourceFilterStats') {
          try {
            return Promise.resolve(JSON.parse(window.WebTvLiveNative.getResourceFilterStats()));
          } catch (e) {
            return Promise.resolve({ enabled: false, total: 0, counts: {} });
          }
        }
        var responses = runtimeListeners.map(function (listener) {
          try { return listener(message); } catch (e) { return undefined; }
        }).filter(function (value) { return value !== undefined; });
        return responses.length ? Promise.resolve(responses[0]) : Promise.resolve();
      },
      onMessage: {
        addListener: function (listener) { runtimeListeners.push(listener); }
      }
    }
  };
})();
"""
    }
}
