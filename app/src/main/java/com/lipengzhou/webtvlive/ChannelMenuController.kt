package com.lipengzhou.webtvlive

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.lipengzhou.webtvlive.databinding.ActivityMainBinding
import kotlin.math.roundToInt

/** Owns the channel/category/program-guide panel and its remote navigation. */
class ChannelMenuController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val panelCoordinator: PanelCoordinator,
    private val beforeOpen: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onInteraction: () -> Unit,
    private val onClosed: () -> Unit,
    private val onChannelChosen: (flatIndex: Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val repository = ProgramGuideRepository(activity.applicationContext)
    private lateinit var categoryAdapter: MenuAdapter
    private lateinit var channelAdapter: MenuAdapter
    private lateinit var programGuideAdapter: ProgramGuideAdapter
    private var categoryIndex = 0
    private var selectedProgramGuidePid: String? = null
    private var selectedProgramGuideDate: String? = null
    private var initialized = false
    private var replayToast: Toast? = null
    private var replayToastShownAt = 0L

    private val loadProgramGuide = Runnable(::loadSelectedProgramGuide)
    private val refreshClock = object : Runnable {
        override fun run() {
            if (!isVisible || !::programGuideAdapter.isInitialized) return
            if (selectedProgramGuideDate != ProgramGuideRepository.today()) {
                scheduleProgramGuideLoad()
            } else {
                val currentIndex = programGuideAdapter.updateNow()
                if (activeColumn() != PanelCoordinator.ChannelColumn.PROGRAM_GUIDE &&
                    currentIndex >= 0
                ) {
                    scrollProgramGuide(currentIndex)
                }
            }
            handler.postDelayed(this, CLOCK_REFRESH_MS)
        }
    }

    val isVisible: Boolean
        get() = panelCoordinator.channelsVisible

    fun open(currentChannelIndex: Int) {
        if (isVisible) return
        beforeOpen()
        setup()
        panelCoordinator.openChannels()

        val (nextCategoryIndex, channelIndex) = TvCatalog.locate(currentChannelIndex)
        categoryIndex = nextCategoryIndex
        categoryAdapter.setSelected(nextCategoryIndex)
        channelAdapter.submit(
            TvCatalog.categories[nextCategoryIndex].channels.map { it.name },
            keepIndex = channelIndex,
        )
        syncColumns()

        binding.menuPanel.visibility = View.VISIBLE
        binding.categoryList.scrollToPosition(nextCategoryIndex)
        binding.channelList.scrollToPosition(channelIndex)
        scheduleProgramGuideLoad()
        handler.removeCallbacks(refreshClock)
        handler.postDelayed(refreshClock, CLOCK_REFRESH_MS)
        onInteraction()
    }

    fun close() {
        if (!isVisible) return
        panelCoordinator.closeChannels()
        selectedProgramGuidePid = null
        selectedProgramGuideDate = null
        handler.removeCallbacks(loadProgramGuide)
        handler.removeCallbacks(refreshClock)
        binding.menuPanel.visibility = View.GONE
        onClosed()
    }

    fun handleKeyDown(keyCode: Int) {
        onInteraction()
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> close()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU -> {
                close()
                onOpenSettings()
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (moveSelection(-1)) playSound(SoundEffectConstants.NAVIGATION_UP)
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (moveSelection(+1)) playSound(SoundEffectConstants.NAVIGATION_DOWN)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val target = when (activeColumn()) {
                    PanelCoordinator.ChannelColumn.PROGRAM_GUIDE ->
                        PanelCoordinator.ChannelColumn.CHANNEL
                    PanelCoordinator.ChannelColumn.CHANNEL,
                    PanelCoordinator.ChannelColumn.CATEGORY ->
                        PanelCoordinator.ChannelColumn.CATEGORY
                }
                if (focusColumn(target)) playSound(SoundEffectConstants.NAVIGATION_LEFT)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val target = when (activeColumn()) {
                    PanelCoordinator.ChannelColumn.CATEGORY ->
                        PanelCoordinator.ChannelColumn.CHANNEL
                    PanelCoordinator.ChannelColumn.CHANNEL -> if (programGuideAdapter.itemCount > 0) {
                        PanelCoordinator.ChannelColumn.PROGRAM_GUIDE
                    } else {
                        PanelCoordinator.ChannelColumn.CHANNEL
                    }
                    PanelCoordinator.ChannelColumn.PROGRAM_GUIDE ->
                        PanelCoordinator.ChannelColumn.PROGRAM_GUIDE
                }
                if (focusColumn(target)) playSound(SoundEffectConstants.NAVIGATION_RIGHT)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (confirmSelection()) playSound(SoundEffectConstants.CLICK)
            }
        }
    }

    fun dispose() {
        handler.removeCallbacksAndMessages(null)
        replayToast?.cancel()
        replayToast = null
        repository.close()
    }

    private fun setup() {
        if (initialized) return
        initialized = true
        categoryAdapter = MenuAdapter(R.layout.item_category, ::onCategoryChosen)
        channelAdapter = MenuAdapter(R.layout.item_channel, ::onChannelRowChosen)
        programGuideAdapter = ProgramGuideAdapter { position ->
            onInteraction()
            focusColumn(PanelCoordinator.ChannelColumn.PROGRAM_GUIDE)
            programGuideAdapter.setSelected(position)
            showReplayUnsupported()
        }

        binding.categoryList.layoutManager = LinearLayoutManager(activity)
        binding.categoryList.adapter = categoryAdapter
        binding.categoryList.itemAnimator = null
        binding.channelList.layoutManager = LinearLayoutManager(activity)
        binding.channelList.adapter = channelAdapter
        binding.channelList.itemAnimator = null
        binding.programGuideList.layoutManager = LinearLayoutManager(activity)
        binding.programGuideList.adapter = programGuideAdapter
        binding.programGuideList.itemAnimator = null
        categoryAdapter.submit(TvCatalog.categories.map { it.name }, keepIndex = 0)
    }

    private fun moveSelection(delta: Int): Boolean {
        when (activeColumn()) {
            PanelCoordinator.ChannelColumn.CATEGORY -> {
                val next = (categoryAdapter.selectedIndex + delta)
                    .coerceIn(0, TvCatalog.categories.lastIndex)
                if (next == categoryAdapter.selectedIndex) return false
                categoryAdapter.setSelected(next)
                binding.categoryList.scrollToPosition(next)
                previewCategory(next)
            }
            PanelCoordinator.ChannelColumn.CHANNEL -> {
                val channels = TvCatalog.categories[categoryIndex].channels
                val next = (channelAdapter.selectedIndex + delta).coerceIn(0, channels.lastIndex)
                if (next == channelAdapter.selectedIndex) return false
                channelAdapter.setSelected(next)
                binding.channelList.scrollToPosition(next)
                scheduleProgramGuideLoad()
            }
            PanelCoordinator.ChannelColumn.PROGRAM_GUIDE -> {
                if (programGuideAdapter.itemCount == 0) return false
                val next = (programGuideAdapter.selectedIndex + delta)
                    .coerceIn(0, programGuideAdapter.itemCount - 1)
                if (next == programGuideAdapter.selectedIndex) return false
                programGuideAdapter.setSelected(next)
                binding.programGuideList.scrollToPosition(next)
            }
        }
        return true
    }

    private fun focusColumn(column: PanelCoordinator.ChannelColumn): Boolean {
        if (!panelCoordinator.focusChannelColumn(column)) return false
        syncColumns()
        return true
    }

    private fun confirmSelection(): Boolean = when (activeColumn()) {
        PanelCoordinator.ChannelColumn.CATEGORY ->
            focusColumn(PanelCoordinator.ChannelColumn.CHANNEL)
        PanelCoordinator.ChannelColumn.CHANNEL -> {
            onChannelRowChosen(channelAdapter.selectedIndex)
            true
        }
        PanelCoordinator.ChannelColumn.PROGRAM_GUIDE -> {
            showReplayUnsupported()
            true
        }
    }

    private fun onCategoryChosen(position: Int) {
        onInteraction()
        categoryAdapter.setSelected(position)
        previewCategory(position)
        focusColumn(PanelCoordinator.ChannelColumn.CHANNEL)
    }

    private fun previewCategory(nextCategoryIndex: Int) {
        categoryIndex = nextCategoryIndex
        channelAdapter.submit(
            TvCatalog.categories[nextCategoryIndex].channels.map { it.name },
            keepIndex = 0,
        )
        binding.channelList.scrollToPosition(0)
        scheduleProgramGuideLoad()
    }

    private fun onChannelRowChosen(position: Int) {
        val flatIndex = TvCatalog.flatIndexOf(categoryIndex, position)
        close()
        onChannelChosen(flatIndex)
    }

    private fun syncColumns() {
        categoryAdapter.setColumnActive(activeColumn() == PanelCoordinator.ChannelColumn.CATEGORY)
        channelAdapter.setColumnActive(activeColumn() == PanelCoordinator.ChannelColumn.CHANNEL)
        programGuideAdapter.setColumnActive(
            activeColumn() == PanelCoordinator.ChannelColumn.PROGRAM_GUIDE,
        )
    }

    private fun scheduleProgramGuideLoad() {
        if (!isVisible) return
        val channel = selectedChannel() ?: return
        selectedProgramGuidePid = channel.pid
        selectedProgramGuideDate = ProgramGuideRepository.today()
        binding.programGuideList.visibility = View.INVISIBLE
        binding.programGuideStatus.visibility = View.GONE
        programGuideAdapter.submit(emptyList())
        handler.removeCallbacks(loadProgramGuide)
        handler.postDelayed(loadProgramGuide, LOAD_DEBOUNCE_MS)
    }

    private fun loadSelectedProgramGuide() {
        if (!isVisible) return
        val channel = selectedChannel() ?: return
        val pid = channel.pid
        if (selectedProgramGuidePid != pid) return
        repository.loadToday(pid) { result ->
            if (!isVisible || selectedProgramGuidePid != pid) return@loadToday
            when (result) {
                ProgramGuideRepository.Result.Loading -> showStatus(R.string.program_guide_loading)
                is ProgramGuideRepository.Result.Data -> showProgramGuide(result.guide)
                is ProgramGuideRepository.Result.Error -> if (!result.hasCachedData) {
                    showStatus(R.string.program_guide_failed)
                }
            }
        }
    }

    private fun showProgramGuide(guide: ProgramGuide) {
        if (guide.items.isEmpty()) {
            showStatus(R.string.program_guide_empty)
            return
        }
        val revealAfterPositioning = binding.programGuideList.visibility != View.VISIBLE
        binding.programGuideStatus.visibility = View.GONE
        if (revealAfterPositioning) binding.programGuideList.visibility = View.INVISIBLE
        val currentIndex = programGuideAdapter.submit(guide.items)
        scrollProgramGuide(
            position = currentIndex.coerceAtLeast(0),
            revealAfterPositioning = revealAfterPositioning,
        )
        syncColumns()
    }

    private fun scrollProgramGuide(position: Int, revealAfterPositioning: Boolean = false) {
        binding.programGuideList.post {
            if (!isVisible || position !in 0 until programGuideAdapter.itemCount) return@post
            val layoutManager = binding.programGuideList.layoutManager as? LinearLayoutManager
                ?: return@post
            val itemHeight = 52f * activity.resources.displayMetrics.density
            val targetOffset = (binding.programGuideList.height / 3 - itemHeight / 2)
                .roundToInt()
                .coerceAtLeast(binding.programGuideList.paddingTop)
            layoutManager.scrollToPositionWithOffset(position, targetOffset)
            if (revealAfterPositioning) binding.programGuideList.visibility = View.VISIBLE
        }
    }

    private fun showStatus(messageRes: Int) {
        programGuideAdapter.submit(emptyList())
        if (activeColumn() == PanelCoordinator.ChannelColumn.PROGRAM_GUIDE) {
            focusColumn(PanelCoordinator.ChannelColumn.CHANNEL)
        }
        binding.programGuideList.visibility = View.GONE
        binding.programGuideStatus.apply {
            visibility = View.VISIBLE
            setText(messageRes)
        }
    }

    private fun showReplayUnsupported() {
        val now = SystemClock.elapsedRealtime()
        if (now - replayToastShownAt < REPLAY_TOAST_THROTTLE_MS) return
        replayToastShownAt = now
        replayToast?.cancel()
        replayToast = Toast.makeText(
            activity,
            R.string.program_guide_replay_unsupported,
            Toast.LENGTH_SHORT,
        ).also(Toast::show)
    }

    private fun selectedChannel(): Channel? = TvCatalog.categories[categoryIndex]
        .channels.getOrNull(channelAdapter.selectedIndex)

    private fun activeColumn(): PanelCoordinator.ChannelColumn = panelCoordinator.channelColumn()

    private fun playSound(soundConstant: Int) {
        binding.menuPanel.playSoundEffect(soundConstant)
    }

    private companion object {
        const val LOAD_DEBOUNCE_MS = 250L
        const val CLOCK_REFRESH_MS = 60_000L
        const val REPLAY_TOAST_THROTTLE_MS = 2_000L
    }
}
