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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.UUID

class FlavorBrowserEngine(private val context: Context) : BrowserEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var listener: BrowserEngine.Listener? = null
    private var adapterScript: String? = null
    private var protocolScript: String? = null
    private var bridgeToken = ""
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
        protocolScript = loadAsset("webextension/protocol.js")
        if (adapterScript == null || protocolScript == null) {
            reportFailure(
                BrowserEngine.FailureKind.INITIALIZATION,
                "WebView 页面适配脚本读取失败",
                recoverable = true,
            )
            return
        }
        bridgeToken = UUID.randomUUID().toString()
        WebView.setWebContentsDebuggingEnabled(isDebuggable())

        val createdView = WebView(container.context)
        createdView.setBackgroundColor(android.graphics.Color.BLACK)
        createdView.addJavascriptInterface(NativeBridge(), BRIDGE_NAME)
        createdView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
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
                if (!TrustedWebContent.isAllowedMainFrameUrl(url)) {
                    view.stopLoading()
                    reportFailure(
                        BrowserEngine.FailureKind.NAVIGATION_BLOCKED,
                        "已阻止非受信任页面：$url",
                        recoverable = false,
                    )
                    return
                }
                bridgeToken = UUID.randomUUID().toString()
                listener.onEvent(BrowserEngine.Event.PageStarted(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!TrustedWebContent.isAllowedMainFrameUrl(url)) return
                listener.onEvent(BrowserEngine.Event.PageStopped(true))
                injectAdapter(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                if (
                    !request.isForMainFrame ||
                    TrustedWebContent.isAllowedMainFrameUrl(request.url.toString())
                ) {
                    return false
                }
                reportFailure(
                    BrowserEngine.FailureKind.NAVIGATION_BLOCKED,
                    "已阻止非受信任页面：${request.url}",
                    recoverable = false,
                )
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    listener.onEvent(BrowserEngine.Event.PageStopped(false))
                    reportFailure(
                        BrowserEngine.FailureKind.MAIN_FRAME,
                        "WebView 主页面加载失败：${error.description}",
                        recoverable = true,
                    )
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                if (webView === view) {
                    bridgeReady = false
                    webView = null
                }
                view.removeJavascriptInterface(BRIDGE_NAME)
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                reportFailure(
                    BrowserEngine.FailureKind.CONTENT_PROCESS,
                    if (detail.didCrash()) "WebView 渲染进程崩溃" else "WebView 渲染进程被系统终止",
                    recoverable = true,
                )
                return true
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
        listener.onEvent(BrowserEngine.Event.Initialized)
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
            BrowserProtocol.encode(BrowserProtocol.Command.SetVideoEnhancement(level)),
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
        bridgeToken = ""
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
        if (!TrustedWebContent.isAllowedMainFrameUrl(view.url)) return
        val protocol = protocolScript ?: return
        val adapter = adapterScript ?: return
        view.evaluateJavascript(runtimeShim(bridgeToken) + "\n" + protocol + "\n" + adapter, null)
    }

    private fun postPendingChannelSwitch() {
        val pending = pendingChannelSwitch ?: return
        postMessage(
            BrowserProtocol.encode(
                BrowserProtocol.Command.SwitchChannel(
                    channel = pending.channel.siteName,
                    pid = pending.channel.pid,
                    requestId = pending.requestId,
                ),
            ),
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
        if (raw == null || raw.toByteArray(Charsets.UTF_8).size > BrowserProtocol.MAX_EVENT_BYTES) {
            reportFailure(
                BrowserEngine.FailureKind.PROTOCOL,
                "已忽略过大的 WebView 页面消息",
                recoverable = false,
            )
            return
        }
        val payload = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        if (payload.optString(KEY_BRIDGE_TOKEN) != bridgeToken ||
            !TrustedWebContent.isAllowedMainFrameUrl(webView?.url)
        ) {
            reportFailure(
                BrowserEngine.FailureKind.PROTOCOL,
                "已忽略来源或令牌无效的 WebView 页面消息",
                recoverable = false,
            )
            return
        }
        payload.remove(KEY_BRIDGE_TOKEN)
        when (val decoded = BrowserProtocol.decodeEvent(payload)) {
            is BrowserProtocol.DecodeResult.Invalid -> reportFailure(
                BrowserEngine.FailureKind.PROTOCOL,
                decoded.reason,
                recoverable = false,
            )

            is BrowserProtocol.DecodeResult.Success -> when (val event = decoded.event) {
                BrowserProtocol.PageEvent.Ready -> {
                    bridgeReady = true
                    applyVideoEnhancement(videoEnhancement)
                    postPendingChannelSwitch()
                }

                is BrowserProtocol.PageEvent.Playing -> listener?.onEvent(
                    BrowserEngine.Event.PlaybackReady(event.requestId),
                )
                is BrowserProtocol.PageEvent.ChannelSelected -> listener?.onEvent(
                    BrowserEngine.Event.ChannelSelected(event.channel),
                )
                is BrowserProtocol.PageEvent.Diagnostic -> listener?.onEvent(
                    BrowserEngine.Event.Diagnostic(event.message),
                )
            }
        }
    }

    private fun reportFailure(
        kind: BrowserEngine.FailureKind,
        detail: String,
        recoverable: Boolean,
    ) {
        listener?.onEvent(BrowserEngine.Event.Failed(BrowserEngine.Failure(kind, detail, recoverable)))
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
        fun getResourceFilterStats(token: String?): String =
            if (token == bridgeToken && TrustedWebContent.isAllowedMainFrameUrl(webView?.url)) {
                resourceFilterStatsJson()
            } else {
                DISABLED_RESOURCE_FILTER_STATS
            }
    }

    companion object {
        private const val TAG = "WebTvLive"
        private const val BRIDGE_NAME = "WebTvLiveNative"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val KEY_BRIDGE_TOKEN = "bridgeToken"
        private const val DISABLED_RESOURCE_FILTER_STATS =
            "{\"enabled\":false,\"total\":0,\"counts\":{}}"
        private const val BRIDGE_TOKEN_PLACEHOLDER = "__WEBTVLIVE_BRIDGE_TOKEN__"
        private const val WEBVIEW_RUNTIME_SHIM = """
(function () {
  if (window.__webTvLiveRuntimeReady) return;
  window.__webTvLiveRuntimeReady = true;
  var nativeListeners = [];
  var runtimeListeners = [];
  function post(payload) {
    try {
      payload.bridgeToken = '__WEBTVLIVE_BRIDGE_TOKEN__';
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
            return Promise.resolve(JSON.parse(
              window.WebTvLiveNative.getResourceFilterStats('__WEBTVLIVE_BRIDGE_TOKEN__')
            ));
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

        fun runtimeShim(token: String): String =
            WEBVIEW_RUNTIME_SHIM.replace(BRIDGE_TOKEN_PLACEHOLDER, token)
    }
}
