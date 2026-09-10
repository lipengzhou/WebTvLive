package com.lipengzhou.webtvlive

/**
 * Owns channel selection and the complete playback retry state machine.
 *
 * The Android layer executes [Attempt.mode], schedules [Attempt.timeoutMs], and feeds the resulting
 * request ID or timeout back into this module. Stale browser events cannot complete a newer attempt.
 */
class PlaybackCoordinator(
    private val channelCount: Int,
    initialChannelIndex: Int,
    private val stableFallbackIndex: Int,
    private val secondaryFallbackIndex: Int,
    private val clockMillis: () -> Long,
    private val timeouts: Timeouts = Timeouts(),
) {
    init {
        require(channelCount > 0) { "频道列表不能为空" }
        require(initialChannelIndex in 0 until channelCount) { "初始频道下标无效" }
    }

    enum class Stage {
        IN_PAGE,
        DIRECT,
        DIRECT_RETRY,
        FALLBACK,
    }

    enum class LoadMode {
        IN_PAGE,
        DIRECT,
    }

    data class Timeouts(
        val inPageMs: Long = 18_000L,
        val directMs: Long = 35_000L,
        val directRetryMs: Long = 25_000L,
        val fallbackMs: Long = 30_000L,
    )

    data class Attempt(
        val attemptId: Long,
        val channelIndex: Int,
        val stage: Stage,
        val mode: LoadMode,
        val requestId: Long,
        val timeoutMs: Long,
        val startedAtMillis: Long,
    )

    data class Ready(
        val channelIndex: Int,
        val stage: Stage,
        val elapsedMillis: Long,
    )

    sealed interface TimeoutResult {
        data class Retry(val attempt: Attempt) : TimeoutResult
        data class Fallback(val attempt: Attempt) : TimeoutResult
        data object Exhausted : TimeoutResult
        data object Stale : TimeoutResult
    }

    var currentChannelIndex: Int = initialChannelIndex
        private set

    var lastSuccessfulChannelIndex: Int = initialChannelIndex
        private set

    private var nextAttemptId = 0L
    private var nextRequestId = 0L
    private var activeAttempt: Attempt? = null

    fun moveSelection(delta: Int): Int {
        currentChannelIndex = ((currentChannelIndex + delta) % channelCount + channelCount) % channelCount
        return currentChannelIndex
    }

    fun selectChannel(index: Int): Boolean {
        require(index in 0 until channelCount) { "频道下标无效：$index" }
        if (index == currentChannelIndex) return false
        currentChannelIndex = index
        return true
    }

    fun start(canSwitchInPage: Boolean): Attempt =
        startAttempt(
            channelIndex = currentChannelIndex,
            stage = if (canSwitchInPage) Stage.IN_PAGE else Stage.DIRECT,
        )

    fun onInPageCommandRejected(attemptId: Long): Attempt? {
        val attempt = activeAttempt ?: return null
        if (attempt.attemptId != attemptId || attempt.stage != Stage.IN_PAGE) return null
        return startAttempt(attempt.channelIndex, Stage.DIRECT)
    }

    fun onPlaybackReady(requestId: Long): Ready? {
        val attempt = activeAttempt ?: return null
        if (attempt.requestId != requestId) return null
        activeAttempt = null
        currentChannelIndex = attempt.channelIndex
        lastSuccessfulChannelIndex = attempt.channelIndex
        return Ready(
            channelIndex = attempt.channelIndex,
            stage = attempt.stage,
            elapsedMillis = (clockMillis() - attempt.startedAtMillis).coerceAtLeast(0L),
        )
    }

    fun onTimeout(attemptId: Long): TimeoutResult {
        val attempt = activeAttempt ?: return TimeoutResult.Stale
        if (attempt.attemptId != attemptId) return TimeoutResult.Stale
        return when (attempt.stage) {
            Stage.IN_PAGE -> TimeoutResult.Retry(
                startAttempt(attempt.channelIndex, Stage.DIRECT),
            )
            Stage.DIRECT -> TimeoutResult.Retry(
                startAttempt(attempt.channelIndex, Stage.DIRECT_RETRY),
            )
            Stage.DIRECT_RETRY -> {
                val fallback = fallbackChannelIndex(attempt.channelIndex)
                if (fallback == attempt.channelIndex) {
                    activeAttempt = null
                    TimeoutResult.Exhausted
                } else {
                    currentChannelIndex = fallback
                    TimeoutResult.Fallback(startAttempt(fallback, Stage.FALLBACK))
                }
            }
            Stage.FALLBACK -> {
                activeAttempt = null
                TimeoutResult.Exhausted
            }
        }
    }

    fun cancel() {
        activeAttempt = null
    }

    private fun startAttempt(channelIndex: Int, stage: Stage): Attempt {
        val attempt = Attempt(
            attemptId = ++nextAttemptId,
            channelIndex = channelIndex,
            stage = stage,
            mode = if (stage == Stage.IN_PAGE) LoadMode.IN_PAGE else LoadMode.DIRECT,
            requestId = ++nextRequestId,
            timeoutMs = when (stage) {
                Stage.IN_PAGE -> timeouts.inPageMs
                Stage.DIRECT -> timeouts.directMs
                Stage.DIRECT_RETRY -> timeouts.directRetryMs
                Stage.FALLBACK -> timeouts.fallbackMs
            },
            startedAtMillis = clockMillis(),
        )
        activeAttempt = attempt
        return attempt
    }

    private fun fallbackChannelIndex(failedIndex: Int): Int {
        if (lastSuccessfulChannelIndex != failedIndex) return lastSuccessfulChannelIndex
        if (stableFallbackIndex in 0 until channelCount && stableFallbackIndex != failedIndex) {
            return stableFallbackIndex
        }
        return if (secondaryFallbackIndex in 0 until channelCount) {
            secondaryFallbackIndex
        } else {
            failedIndex
        }
    }
}
