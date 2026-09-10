package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCatalogTest {
    @Test
    fun pageUrl_pointsToOfficialChannelPage() {
        val cctv13 = TvCatalog.flatChannels.first { it.siteName == "CCTV13" }

        assertEquals(
            "https://www.yangshipin.cn/tv/home?pid=600001811",
            TvCatalog.pageUrl(cctv13),
        )
    }

    @Test
    fun everyChannelHasUniqueSiteNameAndPid() {
        val channels = TvCatalog.flatChannels

        assertTrue(channels.all { it.siteName.isNotBlank() && it.pid.isNotBlank() })
        assertEquals(channels.size, channels.map { it.siteName }.toSet().size)
        assertEquals(channels.size, channels.map { it.pid }.toSet().size)
    }

    @Test
    fun indexOfSiteName_findsStableFallback() {
        val index = TvCatalog.indexOfSiteName("CCTV9")

        assertTrue(index in TvCatalog.flatChannels.indices)
        assertEquals("CCTV-9 纪录", TvCatalog.flatChannels[index].name)
    }
}
