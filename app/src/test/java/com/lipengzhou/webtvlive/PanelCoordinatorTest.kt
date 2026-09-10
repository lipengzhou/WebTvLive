package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelCoordinatorTest {
    @Test
    fun openingOnePanelAlwaysClosesTheOther() {
        val coordinator = PanelCoordinator()
        coordinator.openChannels()
        assertTrue(coordinator.channelsVisible)
        assertFalse(coordinator.settingsVisible)

        coordinator.openSettings()
        assertFalse(coordinator.channelsVisible)
        assertTrue(coordinator.settingsVisible)
    }

    @Test
    fun columnsAreTypedAndValueColumnCanBeRejected() {
        val coordinator = PanelCoordinator()
        coordinator.openChannels()
        assertTrue(coordinator.focusChannelColumn(PanelCoordinator.ChannelColumn.PROGRAM_GUIDE))
        assertEquals(
            PanelCoordinator.ChannelColumn.PROGRAM_GUIDE,
            coordinator.channelColumn(),
        )

        coordinator.openSettings()
        assertFalse(
            coordinator.focusSettingsColumn(
                PanelCoordinator.SettingsColumn.VALUE,
                valueAvailable = false,
            ),
        )
        assertEquals(PanelCoordinator.SettingsColumn.CATEGORY, coordinator.settingsColumn())
    }
}
