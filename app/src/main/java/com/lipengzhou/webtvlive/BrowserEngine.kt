package com.lipengzhou.webtvlive

import android.content.Context
import android.view.View
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

    interface Listener {
        fun onReady()
        fun onPageStarted(url: String)
        fun onPageStopped(success: Boolean)
        fun onPlaybackReady(requestId: Long)
        fun onChannelSelected(channel: String)
        fun onChannelNotFound(channel: String)
        fun onDiagnostic(message: String)
        fun onCrash()
    }
}

fun createBrowserEngine(context: Context): BrowserEngine = FlavorBrowserEngine(context)
