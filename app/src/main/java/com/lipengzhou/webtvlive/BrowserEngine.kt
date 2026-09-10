package com.lipengzhou.webtvlive

import android.content.Context
import android.view.ViewGroup

/** Browser-kernel bridge used by the shared TV/channel UI. */
interface BrowserEngine {
    fun attach(container: ViewGroup, listener: Listener)
    fun canSwitchInPage(): Boolean
    fun loadChannel(channel: Channel, requestId: Long)
    fun switchChannel(channel: Channel, requestId: Long): Boolean
    fun applyVideoEnhancement(level: VideoEnhancement)
    fun onResume()
    fun onPause()
    fun destroy()

    fun interface Listener {
        fun onEvent(event: Event)
    }

    sealed interface Event {
        data object Initialized : Event
        data class PageStarted(val url: String) : Event
        data class PageStopped(val success: Boolean) : Event
        data class PlaybackReady(val requestId: Long) : Event
        data class ChannelSelected(val channel: String) : Event
        data class Diagnostic(val message: String) : Event
        data class Failed(val failure: Failure) : Event
    }

    data class Failure(
        val kind: FailureKind,
        val detail: String,
        val recoverable: Boolean,
    )

    enum class FailureKind {
        INITIALIZATION,
        MAIN_FRAME,
        CONTENT_PROCESS,
        NAVIGATION_BLOCKED,
        PROTOCOL,
    }
}

fun createBrowserEngine(context: Context): BrowserEngine = FlavorBrowserEngine(context)
