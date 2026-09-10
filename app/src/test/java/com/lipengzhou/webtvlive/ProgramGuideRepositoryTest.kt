package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class ProgramGuideRepositoryTest {
    @Test
    fun freshDiskCacheReturnsWithoutNetwork() {
        val cache = FakeCache().apply {
            entry = CachedProgramGuide(DATE, 900, guide("缓存节目"))
        }
        var downloads = 0
        val results = mutableListOf<ProgramGuideRepository.Result>()
        val repository = repository(cache) { _, _ ->
            downloads += 1
            guide("网络节目")
        }

        repository.loadToday(PID, results::add)

        assertEquals(0, downloads)
        assertEquals(2, results.size)
        assertEquals(ProgramGuideRepository.Result.Loading, results[0])
        val cached = results[1] as ProgramGuideRepository.Result.Data
        assertTrue(cached.fromCache)
        assertEquals("缓存节目", cached.guide.items.single().name)
    }

    @Test
    fun staleDiskCacheIsShownBeforeNetworkRefresh() {
        val cache = FakeCache().apply {
            entry = CachedProgramGuide(DATE, -2_000_000, guide("旧节目"))
        }
        val results = mutableListOf<ProgramGuideRepository.Result>()
        val repository = repository(cache) { _, _ -> guide("新节目") }

        repository.loadToday(PID, results::add)

        assertEquals(3, results.size)
        assertTrue((results[1] as ProgramGuideRepository.Result.Data).fromCache)
        val refreshed = results[2] as ProgramGuideRepository.Result.Data
        assertFalse(refreshed.fromCache)
        assertEquals("新节目", refreshed.guide.items.single().name)
        assertEquals("新节目", cache.entry?.guide?.items?.single()?.name)
    }

    @Test
    fun cacheWriteFailureDoesNotDiscardDownloadedGuide() {
        val cache = FakeCache(failWrites = true)
        val results = mutableListOf<ProgramGuideRepository.Result>()
        val repository = repository(cache) { _, _ -> guide("网络节目") }

        repository.loadToday(PID, results::add)

        val downloaded = results.last() as ProgramGuideRepository.Result.Data
        assertFalse(downloaded.fromCache)
        assertEquals("网络节目", downloaded.guide.items.single().name)
    }

    private fun repository(cache: FakeCache, client: EpgClient) = ProgramGuideRepository(
        cache = cache,
        client = client,
        executor = DirectExecutorService(),
        callbackDispatcher = { it() },
        todayProvider = { DATE },
        nowMillis = { 1_000 },
        logger = { _, _ -> },
    )

    private fun guide(name: String) = ProgramGuide(
        updateTimeEpochSeconds = 1,
        items = listOf(
            ProgramGuideItem("id", name, 1, 2, "00:00", "00:01", 60, false, "", ""),
        ),
    )

    private class FakeCache(private val failWrites: Boolean = false) : ProgramGuideCache {
        var entry: CachedProgramGuide? = null

        override fun read(key: String, expectedDate: String): CachedProgramGuide? = entry

        override fun write(key: String, entry: CachedProgramGuide) {
            if (failWrites) error("disk full")
            this.entry = entry
        }

        override fun removeOtherDates(currentDate: String) = Unit
    }

    private class DirectExecutorService : AbstractExecutorService() {
        private var shutdown = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): List<Runnable> { shutdown = true; return emptyList() }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown
    }

    private companion object {
        const val PID = "600001811"
        const val DATE = "20260910"
    }
}
