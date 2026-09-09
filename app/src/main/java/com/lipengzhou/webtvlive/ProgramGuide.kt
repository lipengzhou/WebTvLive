package com.lipengzhou.webtvlive

/** 央视频当天节目单中的一项。时间戳单位均为秒。 */
data class ProgramGuideItem(
    val programId: String,
    val name: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val startTime: String,
    val endTime: String,
    val durationSeconds: Int,
    val isVip: Boolean,
    val copyrightFlag: String,
    val timeShiftReviewFlag: String,
) {
    fun isPlayingAt(epochSeconds: Long): Boolean =
        epochSeconds in startEpochSeconds until endEpochSeconds
}

data class ProgramGuide(
    val updateTimeEpochSeconds: Long,
    val items: List<ProgramGuideItem>,
)
