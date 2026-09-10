package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {
    private var now = 1_000L

    @Test
    fun stalePlayingCannotCompleteNewAttempt() {
        val coordinator = coordinator(initial = 2)
        val first = coordinator.start(canSwitchInPage = true)
        coordinator.moveSelection(1)
        val second = coordinator.start(canSwitchInPage = true)

        assertNull(coordinator.onPlaybackReady(first.requestId))
        now += 450
        val ready = coordinator.onPlaybackReady(second.requestId)

        assertEquals(3, ready?.channelIndex)
        assertEquals(450L, ready?.elapsedMillis)
        assertEquals(3, coordinator.lastSuccessfulChannelIndex)
    }

    @Test
    fun failedInPageCommandFallsBackToDirectLoad() {
        val coordinator = coordinator(initial = 4)
        val inPage = coordinator.start(canSwitchInPage = true)

        val direct = coordinator.onInPageCommandRejected(inPage.attemptId)

        assertEquals(PlaybackCoordinator.Stage.DIRECT, direct?.stage)
        assertEquals(PlaybackCoordinator.LoadMode.DIRECT, direct?.mode)
        assertEquals(4, direct?.channelIndex)
    }

    @Test
    fun timeoutSequenceRetriesThenFallsBackThenStops() {
        val coordinator = coordinator(initial = 4)
        val inPage = coordinator.start(canSwitchInPage = true)
        val direct = (coordinator.onTimeout(inPage.attemptId) as PlaybackCoordinator.TimeoutResult.Retry)
            .attempt
        val retry = (coordinator.onTimeout(direct.attemptId) as PlaybackCoordinator.TimeoutResult.Retry)
            .attempt
        val fallback = (
            coordinator.onTimeout(retry.attemptId) as PlaybackCoordinator.TimeoutResult.Fallback
            ).attempt

        assertEquals(PlaybackCoordinator.Stage.DIRECT, direct.stage)
        assertEquals(PlaybackCoordinator.Stage.DIRECT_RETRY, retry.stage)
        assertEquals(7, fallback.channelIndex)
        assertEquals(PlaybackCoordinator.Stage.FALLBACK, fallback.stage)
        assertTrue(
            coordinator.onTimeout(fallback.attemptId) is PlaybackCoordinator.TimeoutResult.Exhausted,
        )
    }

    @Test
    fun channelSelectionWrapsAndFallbackPrefersLastSuccessfulChannel() {
        val coordinator = coordinator(initial = 0)
        assertEquals(9, coordinator.moveSelection(-1))
        val readyAttempt = coordinator.start(canSwitchInPage = false)
        coordinator.onPlaybackReady(readyAttempt.requestId)

        coordinator.selectChannel(4)
        val failed = coordinator.start(canSwitchInPage = false)
        val retry = (coordinator.onTimeout(failed.attemptId) as PlaybackCoordinator.TimeoutResult.Retry)
            .attempt
        val fallback = (
            coordinator.onTimeout(retry.attemptId) as PlaybackCoordinator.TimeoutResult.Fallback
            ).attempt

        assertEquals(9, fallback.channelIndex)
    }

    private fun coordinator(initial: Int) = PlaybackCoordinator(
        channelCount = 10,
        initialChannelIndex = initial,
        stableFallbackIndex = 7,
        secondaryFallbackIndex = 8,
        clockMillis = { now },
        timeouts = PlaybackCoordinator.Timeouts(1, 2, 3, 4),
    )
}
