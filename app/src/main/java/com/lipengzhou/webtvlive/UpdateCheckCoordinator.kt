package com.lipengzhou.webtvlive

/** Serializes automatic and manual update checks without exposing boolean combinations to the UI. */
class UpdateCheckCoordinator {
    enum class Mode {
        AUTOMATIC,
        MANUAL,
    }

    sealed interface RequestResult {
        data class Start(val mode: Mode) : RequestResult
        data object Queued : RequestResult
        data object Ignored : RequestResult
    }

    sealed interface Completion {
        data class Deliver(val mode: Mode) : Completion
        data object StartQueuedManual : Completion
        data object Stale : Completion
    }

    private var automaticRequested = false
    private var activeMode: Mode? = null
    private var manualQueued = false

    fun requestAutomatic(): RequestResult {
        if (automaticRequested) return RequestResult.Ignored
        automaticRequested = true
        if (activeMode != null) return RequestResult.Ignored
        activeMode = Mode.AUTOMATIC
        return RequestResult.Start(Mode.AUTOMATIC)
    }

    fun requestManual(): RequestResult {
        automaticRequested = true
        return when (activeMode) {
            null -> {
                activeMode = Mode.MANUAL
                RequestResult.Start(Mode.MANUAL)
            }
            Mode.AUTOMATIC -> {
                manualQueued = true
                RequestResult.Queued
            }
            Mode.MANUAL -> RequestResult.Ignored
        }
    }

    fun complete(mode: Mode): Completion {
        if (activeMode != mode) return Completion.Stale
        activeMode = null
        if (manualQueued) {
            manualQueued = false
            activeMode = Mode.MANUAL
            return Completion.StartQueuedManual
        }
        return Completion.Deliver(mode)
    }
}
