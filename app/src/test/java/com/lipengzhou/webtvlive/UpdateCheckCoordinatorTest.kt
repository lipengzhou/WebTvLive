package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckCoordinatorTest {
    @Test
    fun manualCheckQueuesBehindAutomaticAndReplacesItsPresentation() {
        val coordinator = UpdateCheckCoordinator()

        assertEquals(
            UpdateCheckCoordinator.RequestResult.Start(UpdateCheckCoordinator.Mode.AUTOMATIC),
            coordinator.requestAutomatic(),
        )
        assertEquals(UpdateCheckCoordinator.RequestResult.Queued, coordinator.requestManual())
        assertEquals(
            UpdateCheckCoordinator.Completion.StartQueuedManual,
            coordinator.complete(UpdateCheckCoordinator.Mode.AUTOMATIC),
        )
        assertEquals(
            UpdateCheckCoordinator.Completion.Deliver(UpdateCheckCoordinator.Mode.MANUAL),
            coordinator.complete(UpdateCheckCoordinator.Mode.MANUAL),
        )
    }

    @Test
    fun automaticCheckRunsOnlyOnceAndStaleCompletionIsIgnored() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.requestAutomatic()

        assertEquals(UpdateCheckCoordinator.RequestResult.Ignored, coordinator.requestAutomatic())
        assertEquals(
            UpdateCheckCoordinator.Completion.Stale,
            coordinator.complete(UpdateCheckCoordinator.Mode.MANUAL),
        )
        assertEquals(
            UpdateCheckCoordinator.Completion.Deliver(UpdateCheckCoordinator.Mode.AUTOMATIC),
            coordinator.complete(UpdateCheckCoordinator.Mode.AUTOMATIC),
        )
    }
}
