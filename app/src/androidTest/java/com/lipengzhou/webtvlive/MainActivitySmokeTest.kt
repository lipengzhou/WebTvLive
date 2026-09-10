package com.lipengzhou.webtvlive

import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun remoteKeysKeepSidePanelsMutuallyExclusive() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.menuPanel).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsPanel).visibility)

                press(activity, KeyEvent.KEYCODE_MENU)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.menuPanel).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsPanel).visibility)

                press(activity, KeyEvent.KEYCODE_BACK)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsPanel).visibility)
            }
        }
    }

    private fun press(activity: MainActivity, keyCode: Int) {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
