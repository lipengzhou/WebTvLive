package com.lipengzhou.webtvlive

/** Keeps the mutually exclusive TV side panels and their active columns in one valid state. */
class PanelCoordinator {
    enum class ChannelColumn {
        CATEGORY,
        CHANNEL,
        PROGRAM_GUIDE,
    }

    enum class SettingsColumn {
        CATEGORY,
        VALUE,
    }

    sealed interface State {
        data object Closed : State
        data class Channels(val activeColumn: ChannelColumn) : State
        data class Settings(val activeColumn: SettingsColumn) : State
    }

    var state: State = State.Closed
        private set

    val channelsVisible: Boolean
        get() = state is State.Channels

    val settingsVisible: Boolean
        get() = state is State.Settings

    fun openChannels() {
        state = State.Channels(ChannelColumn.CHANNEL)
    }

    fun openSettings() {
        state = State.Settings(SettingsColumn.CATEGORY)
    }

    fun closeChannels(): Boolean {
        if (!channelsVisible) return false
        state = State.Closed
        return true
    }

    fun closeSettings(): Boolean {
        if (!settingsVisible) return false
        state = State.Closed
        return true
    }

    fun focusChannelColumn(column: ChannelColumn): Boolean {
        val current = state as? State.Channels ?: return false
        if (current.activeColumn == column) return false
        state = current.copy(activeColumn = column)
        return true
    }

    fun focusSettingsColumn(column: SettingsColumn, valueAvailable: Boolean = true): Boolean {
        val current = state as? State.Settings ?: return false
        if (column == SettingsColumn.VALUE && !valueAvailable) return false
        if (current.activeColumn == column) return false
        state = current.copy(activeColumn = column)
        return true
    }

    fun channelColumn(): ChannelColumn =
        (state as? State.Channels)?.activeColumn ?: ChannelColumn.CHANNEL

    fun settingsColumn(): SettingsColumn =
        (state as? State.Settings)?.activeColumn ?: SettingsColumn.CATEGORY
}
