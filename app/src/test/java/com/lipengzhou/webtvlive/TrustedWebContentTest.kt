package com.lipengzhou.webtvlive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedWebContentTest {
    @Test
    fun allowsOnlyOfficialHttpsTvMainFrame() {
        assertTrue(
            TrustedWebContent.isAllowedMainFrameUrl(
                "https://www.yangshipin.cn/tv/home?pid=600001811",
            ),
        )
        assertTrue(TrustedWebContent.isAllowedMainFrameUrl("https://www.yangshipin.cn/tv/home/"))
        assertFalse(TrustedWebContent.isAllowedMainFrameUrl("http://www.yangshipin.cn/tv/home"))
        assertFalse(TrustedWebContent.isAllowedMainFrameUrl("https://yangshipin.cn/tv/home"))
        assertFalse(TrustedWebContent.isAllowedMainFrameUrl("https://www.yangshipin.cn/account"))
        assertFalse(
            TrustedWebContent.isAllowedMainFrameUrl(
                "https://www.yangshipin.cn.evil.example/tv/home",
            ),
        )
    }
}
