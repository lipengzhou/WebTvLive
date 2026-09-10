package com.lipengzhou.webtvlive

import java.net.URI

/** Main-frame navigation policy for pages allowed to communicate with the native app. */
object TrustedWebContent {
    fun isAllowedMainFrameUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host == YANGSHIPIN_HOST &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.path.trimEnd('/') == YANGSHIPIN_TV_PATH
    }

    private const val YANGSHIPIN_HOST = "www.yangshipin.cn"
    private const val YANGSHIPIN_TV_PATH = "/tv/home"
}
