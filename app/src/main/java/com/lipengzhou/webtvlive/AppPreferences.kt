package com.lipengzhou.webtvlive

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the app-wide `webtvlive_prefs` store and its keys.
 *
 * [MainActivity], [SettingsPanelController] and [PlaybackTouchController] all read/write this one
 * file, so the file name and key strings live here instead of being re-declared per class. The
 * update tracker (`app_update_prefs`) and program-guide cache (`program_guide_cache`) are separate
 * stores owned by their respective classes and are intentionally not centralized here.
 */
object AppPreferences {
    const val FILE_NAME = "webtvlive_prefs"

    // 上/下换台记住的一维频道下标（含仅记录成功起播的兼容键）。
    const val KEY_LAST_CHANNEL = "last_channel_index"
    const val KEY_LAST_SUCCESSFUL_CHANNEL = "last_successful_channel_index"

    // 设置面板：画质增强档位与「换台方向反转」开关。
    const val KEY_VIDEO_ENHANCEMENT = "video_enhancement"
    const val KEY_CHANNEL_SWITCH_REVERSED = "channel_switch_reversed"

    // 触屏左半屏滑动记住的应用内亮度。
    const val KEY_BRIGHTNESS = "playback_brightness"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
