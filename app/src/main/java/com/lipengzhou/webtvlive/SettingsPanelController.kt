package com.lipengzhou.webtvlive

import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import com.lipengzhou.webtvlive.databinding.ActivityMainBinding

/** Owns settings state, persistence, rendering and remote-control navigation. */
class SettingsPanelController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val panelCoordinator: PanelCoordinator,
    private val beforeOpen: () -> Unit,
    private val onInteraction: () -> Unit,
    private val onClosed: () -> Unit,
    private val onVideoEnhancementChanged: (VideoEnhancement) -> Unit,
    private val onManualUpdateCheck: () -> Unit,
) {
    private enum class Item(val titleRes: Int) {
        VIDEO_ENHANCEMENT(R.string.setting_video_enhancement),
        CHANNEL_SWITCH_REVERSE(R.string.setting_channel_switch_reverse),
        CHECK_UPDATE(R.string.setting_check_update),
        ABOUT(R.string.setting_about),
    }

    private val preferences = AppPreferences.of(activity)
    private val availableItems = Item.entries.filter { item ->
        BuildConfig.APP_UPDATES_ENABLED || item != Item.CHECK_UPDATE
    }
    private lateinit var categoryAdapter: MenuAdapter
    private lateinit var valueAdapter: MenuAdapter
    private var initialized = false

    var videoEnhancement: VideoEnhancement = VideoEnhancement.fromWireValue(
        preferences.getString(AppPreferences.KEY_VIDEO_ENHANCEMENT, null),
    )
        private set

    var channelSwitchReversed: Boolean =
        preferences.getBoolean(AppPreferences.KEY_CHANNEL_SWITCH_REVERSED, false)
        private set

    val isVisible: Boolean
        get() = panelCoordinator.settingsVisible

    fun open() {
        if (isVisible) return
        beforeOpen()
        setup()
        panelCoordinator.openSettings()
        categoryAdapter.setSelected(0)
        updateValues()
        syncColumnActive()
        binding.settingsPanel.visibility = View.VISIBLE
        binding.settingsCategoryList.scrollToPosition(categoryAdapter.selectedIndex)
        binding.settingsValueList.scrollToPosition(valueAdapter.selectedIndex)
        onInteraction()
    }

    fun close() {
        if (!isVisible) return
        panelCoordinator.closeSettings()
        binding.settingsPanel.visibility = View.GONE
        onClosed()
    }

    fun handleKeyDown(keyCode: Int) {
        onInteraction()
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU -> close()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (moveSelection(-1)) playSound(SoundEffectConstants.NAVIGATION_UP)
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (moveSelection(+1)) playSound(SoundEffectConstants.NAVIGATION_DOWN)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (selectedItem().hasSelectableValues() &&
                    focusColumn(PanelCoordinator.SettingsColumn.VALUE)
                ) {
                    playSound(SoundEffectConstants.NAVIGATION_LEFT)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusColumn(PanelCoordinator.SettingsColumn.CATEGORY)) {
                    playSound(SoundEffectConstants.NAVIGATION_RIGHT)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (activeColumn() == PanelCoordinator.SettingsColumn.CATEGORY) {
                    if (selectedItem().hasSelectableValues()) {
                        focusColumn(PanelCoordinator.SettingsColumn.VALUE)
                    }
                } else {
                    selectValue(valueAdapter.selectedIndex)
                }
                playSound(SoundEffectConstants.CLICK)
            }
        }
    }

    private fun setup() {
        if (initialized) return
        initialized = true
        categoryAdapter = MenuAdapter(R.layout.item_category) { position ->
            categoryAdapter.setSelected(position)
            updateValues()
            if (selectedItem().hasSelectableValues()) {
                focusColumn(PanelCoordinator.SettingsColumn.VALUE)
            }
            onInteraction()
        }
        valueAdapter = MenuAdapter(R.layout.item_channel, ::selectValue)
        binding.settingsCategoryList.layoutManager = LinearLayoutManager(activity)
        binding.settingsCategoryList.adapter = categoryAdapter
        binding.settingsCategoryList.itemAnimator = null
        binding.settingsValueList.layoutManager = LinearLayoutManager(activity)
        binding.settingsValueList.adapter = valueAdapter
        binding.settingsValueList.itemAnimator = null
        categoryAdapter.submit(availableItems.map { activity.getString(it.titleRes) }, keepIndex = 0)
    }

    private fun moveSelection(delta: Int): Boolean {
        if (activeColumn() == PanelCoordinator.SettingsColumn.CATEGORY) {
            val next = (categoryAdapter.selectedIndex + delta).coerceIn(0, availableItems.lastIndex)
            if (next == categoryAdapter.selectedIndex) return false
            categoryAdapter.setSelected(next)
            binding.settingsCategoryList.scrollToPosition(next)
            updateValues()
        } else {
            if (!selectedItem().hasSelectableValues()) return false
            val next = (valueAdapter.selectedIndex + delta).coerceIn(0, valueMaxIndex())
            if (next == valueAdapter.selectedIndex) return false
            valueAdapter.setSelected(next)
            binding.settingsValueList.scrollToPosition(next)
        }
        return true
    }

    private fun focusColumn(column: PanelCoordinator.SettingsColumn): Boolean {
        if (!panelCoordinator.focusSettingsColumn(
                column,
                valueAvailable = selectedItem().hasSelectableValues(),
            )
        ) return false
        syncColumnActive()
        return true
    }

    private fun syncColumnActive() {
        categoryAdapter.setColumnActive(activeColumn() == PanelCoordinator.SettingsColumn.CATEGORY)
        valueAdapter.setColumnActive(activeColumn() == PanelCoordinator.SettingsColumn.VALUE)
    }

    private fun updateValues() {
        when (selectedItem()) {
            Item.VIDEO_ENHANCEMENT -> showSelectableValues(
                labels = videoEnhancementLabels(),
                selectedIndex = VideoEnhancement.entries.indexOf(videoEnhancement),
            )
            Item.CHANNEL_SWITCH_REVERSE -> showSelectableValues(
                labels = channelSwitchReverseLabels(),
                selectedIndex = if (channelSwitchReversed) 1 else 0,
            )
            Item.CHECK_UPDATE -> showSelectableValues(
                labels = listOf(activity.getString(R.string.setting_check_update_now)),
                selectedIndex = 0,
            )
            Item.ABOUT -> {
                panelCoordinator.focusSettingsColumn(PanelCoordinator.SettingsColumn.CATEGORY)
                binding.settingsValueList.visibility = View.GONE
                binding.settingsAboutText.text = aboutText()
                binding.settingsAboutText.visibility = View.VISIBLE
                valueAdapter.submit(emptyList(), keepIndex = 0)
                syncColumnActive()
            }
        }
    }

    private fun showSelectableValues(labels: List<String>, selectedIndex: Int) {
        binding.settingsAboutText.visibility = View.GONE
        binding.settingsValueList.visibility = View.VISIBLE
        valueAdapter.submit(labels, keepIndex = selectedIndex)
        valueAdapter.setColumnActive(activeColumn() == PanelCoordinator.SettingsColumn.VALUE)
        binding.settingsValueList.scrollToPosition(selectedIndex)
    }

    private fun selectValue(position: Int) {
        onInteraction()
        when (selectedItem()) {
            Item.VIDEO_ENHANCEMENT -> selectVideoEnhancement(position)
            Item.CHANNEL_SWITCH_REVERSE -> selectChannelSwitchReverse(position)
            Item.CHECK_UPDATE -> onManualUpdateCheck()
            Item.ABOUT -> Unit
        }
    }

    private fun selectVideoEnhancement(position: Int) {
        val selected = VideoEnhancement.entries.getOrNull(position) ?: return
        videoEnhancement = selected
        preferences.edit { putString(AppPreferences.KEY_VIDEO_ENHANCEMENT, selected.wireValue) }
        valueAdapter.submit(videoEnhancementLabels(), keepIndex = position)
        valueAdapter.setColumnActive(activeColumn() == PanelCoordinator.SettingsColumn.VALUE)
        onVideoEnhancementChanged(selected)
    }

    private fun selectChannelSwitchReverse(position: Int) {
        channelSwitchReversed = position == 1
        preferences.edit { putBoolean(AppPreferences.KEY_CHANNEL_SWITCH_REVERSED, channelSwitchReversed) }
        valueAdapter.submit(channelSwitchReverseLabels(), keepIndex = position)
        valueAdapter.setColumnActive(activeColumn() == PanelCoordinator.SettingsColumn.VALUE)
    }

    private fun videoEnhancementLabels(): List<String> = VideoEnhancement.entries.map { level ->
        activity.getString(level.labelRes) + if (level == videoEnhancement) "  ✓" else ""
    }

    private fun channelSwitchReverseLabels(): List<String> = listOf(
        activity.getString(R.string.setting_off) + if (!channelSwitchReversed) "  ✓" else "",
        activity.getString(R.string.setting_on) + if (channelSwitchReversed) "  ✓" else "",
    )

    private fun aboutText(): String {
        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return listOf(
            activity.getString(R.string.about_developer),
            activity.getString(R.string.about_engine, activity.getString(R.string.browser_engine_name)),
            activity.getString(
                R.string.about_version,
                packageInfo.versionName ?: activity.getString(R.string.about_version_unknown),
                packageInfo.longVersionCode,
            ),
        ).joinToString(separator = "\n")
    }

    private fun valueMaxIndex(): Int = when (selectedItem()) {
        Item.VIDEO_ENHANCEMENT -> VideoEnhancement.entries.lastIndex
        Item.CHANNEL_SWITCH_REVERSE -> 1
        Item.CHECK_UPDATE, Item.ABOUT -> 0
    }

    private fun selectedItem(): Item = availableItems.getOrElse(categoryAdapter.selectedIndex) {
        Item.VIDEO_ENHANCEMENT
    }

    private fun Item.hasSelectableValues(): Boolean = this != Item.ABOUT

    private fun activeColumn(): PanelCoordinator.SettingsColumn = panelCoordinator.settingsColumn()

    private fun playSound(soundConstant: Int) {
        binding.settingsPanel.playSoundEffect(soundConstant)
    }
}
