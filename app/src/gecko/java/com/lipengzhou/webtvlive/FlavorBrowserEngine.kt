package com.lipengzhou.webtvlive

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

class FlavorBrowserEngine(context: Context) : BrowserEngine {

    private val appContext = context.applicationContext
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession? = null
    private var extensionPort: WebExtension.Port? = null
    private var listener: BrowserEngine.Listener? = null
    private var pendingChannelSwitch: PendingChannelSwitch? = null
    private var videoEnhancement = VideoEnhancement.ORIGINAL

    private data class PendingChannelSwitch(
        val channel: Channel,
        val requestId: Long,
    )

    override fun attach(container: ViewGroup, listener: BrowserEngine.Listener) {
        this.listener = listener

        val createdView = GeckoView(container.context)
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
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) = Unit

            override fun onCrash(session: GeckoSession) {
                listener.onEvent(
                    BrowserEngine.Event.Failed(
                        BrowserEngine.Failure(
                            kind = BrowserEngine.FailureKind.CONTENT_PROCESS,
                            detail = "Gecko 内容进程崩溃",
                            recoverable = true,
                        ),
                    ),
                )
            }

            override fun onKill(session: GeckoSession) {
                listener.onEvent(
                    BrowserEngine.Event.Failed(
                        BrowserEngine.Failure(
                            kind = BrowserEngine.FailureKind.CONTENT_PROCESS,
                            detail = "Gecko 内容进程被系统终止",
                            recoverable = true,
                        ),
                    ),
                )
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                listener.onEvent(BrowserEngine.Event.PageStarted(url))
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                listener.onEvent(BrowserEngine.Event.PageStopped(success))
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    listener.onEvent(
                        BrowserEngine.Event.Diagnostic(
                            "Blocked new browser window: ${request.uri}",
                        ),
                    )
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                val allowed = TrustedWebContent.isAllowedMainFrameUrl(request.uri)
                if (!allowed) {
                    reportFailure(
                        BrowserEngine.FailureKind.NAVIGATION_BLOCKED,
                        "已阻止非受信任页面：${request.uri}",
                        recoverable = false,
                    )
                }
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
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

        val runtime = (appContext as WebTvLiveApplication).geckoRuntime
        session.open(runtime)
        session.setActive(true)
        session.setFocused(true)
        createdView.setSession(session)
        geckoView = createdView
        geckoSession = session
        container.addView(
            createdView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        installWebExtension(runtime, session)
    }

    override fun canSwitchInPage(): Boolean = extensionPort != null

    override fun loadChannel(channel: Channel, requestId: Long) {
        pendingChannelSwitch = PendingChannelSwitch(channel, requestId)
        extensionPort?.disconnect()
        extensionPort = null
        geckoSession?.stop()
        geckoSession?.loadUri(TvCatalog.pageUrl(channel))
    }

    override fun switchChannel(channel: Channel, requestId: Long): Boolean {
        val port = extensionPort ?: return false
        return postChannelSwitch(port, channel, requestId)
    }

    override fun applyVideoEnhancement(level: VideoEnhancement) {
        videoEnhancement = level
        val port = extensionPort ?: return
        try {
            port.postMessage(BrowserProtocol.encode(BrowserProtocol.Command.SetVideoEnhancement(level)))
            Log.i(TAG, "Video enhancement requested: ${level.wireValue}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to send video enhancement through WebExtension port", error)
        }
    }

    override fun onResume() {
        geckoSession?.setActive(true)
        geckoSession?.setFocused(true)
    }

    override fun onPause() {
        geckoSession?.setFocused(false)
        geckoSession?.setActive(false)
    }

    override fun destroy() {
        pendingChannelSwitch = null
        extensionPort?.disconnect()
        extensionPort = null
        geckoView?.releaseSession()
        geckoSession?.close()
        geckoView?.parent?.let { parent ->
            if (parent is ViewGroup) parent.removeView(geckoView)
        }
        geckoView = null
        geckoSession = null
        listener = null
    }

    private fun installWebExtension(runtime: GeckoRuntime, session: GeckoSession) {
        runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { extension ->
                    if (extension == null) {
                        Log.e(TAG, "GeckoView extension install returned null")
                        reportFailure(
                            BrowserEngine.FailureKind.INITIALIZATION,
                            "GeckoView 页面适配扩展安装结果为空",
                            recoverable = true,
                        )
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
                                            if (extensionPort === port) extensionPort = null
                                        }
                                    },
                                )
                                extensionPort = port
                                Log.i(TAG, "WebExtension native port connected")
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
                    listener?.onEvent(BrowserEngine.Event.Initialized)
                },
                { error ->
                    Log.e(TAG, "Unable to install GeckoView extension", error)
                    reportFailure(
                        BrowserEngine.FailureKind.INITIALIZATION,
                        "GeckoView 页面适配扩展安装失败：${error?.message.orEmpty()}",
                        recoverable = true,
                    )
                },
            )
    }

    private fun handleExtensionMessage(message: Any, port: WebExtension.Port? = null) {
        when (val decoded = BrowserProtocol.decodeEvent(message)) {
            is BrowserProtocol.DecodeResult.Invalid -> {
                reportFailure(
                    BrowserEngine.FailureKind.PROTOCOL,
                    decoded.reason,
                    recoverable = false,
                )
            }

            is BrowserProtocol.DecodeResult.Success -> when (val event = decoded.event) {
                BrowserProtocol.PageEvent.Ready -> {
                    val activePort = port ?: extensionPort ?: return
                    extensionPort = activePort
                    applyVideoEnhancement(videoEnhancement)
                    pendingChannelSwitch?.let {
                        if (postChannelSwitch(activePort, it.channel, it.requestId)) {
                            pendingChannelSwitch = null
                        }
                    }
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

    private fun postChannelSwitch(
        port: WebExtension.Port,
        channel: Channel,
        requestId: Long,
    ): Boolean {
        return try {
            port.postMessage(
                BrowserProtocol.encode(
                    BrowserProtocol.Command.SwitchChannel(
                        channel = channel.siteName,
                        pid = channel.pid,
                        requestId = requestId,
                    ),
                ),
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to send channel switch through WebExtension port", error)
            if (extensionPort === port) extensionPort = null
            false
        }
    }

    private fun reportFailure(
        kind: BrowserEngine.FailureKind,
        detail: String,
        recoverable: Boolean,
    ) {
        listener?.onEvent(BrowserEngine.Event.Failed(BrowserEngine.Failure(kind, detail, recoverable)))
    }

    companion object {
        private const val TAG = "WebTvLive"
        private const val EXTENSION_LOCATION = "resource://android/assets/webextension/"
        private const val EXTENSION_ID = "webtvlive@lipengzhou.com"
        private const val NATIVE_APP_ID = "webtvlive"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
