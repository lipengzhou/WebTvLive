package com.lipengzhou.webtvlive

import androidx.annotation.StringRes

/** 可由原生设置面板下发给 WebExtension 的画质增强档位。 */
enum class VideoEnhancement(
    val wireValue: String,
    @param:StringRes val labelRes: Int,
) {
    ORIGINAL("original", R.string.video_enhancement_original),
    LIGHT("light", R.string.video_enhancement_light),
    STANDARD("standard", R.string.video_enhancement_standard),
    STRONG("strong", R.string.video_enhancement_strong),
    ;

    companion object {
        fun fromWireValue(value: String?): VideoEnhancement =
            entries.firstOrNull { it.wireValue == value } ?: ORIGINAL
    }
}
