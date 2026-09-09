package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildIsolationTest {

    @Test
    fun buildTypeUsesTheExpectedApplicationIdentity() {
        when (BuildConfig.BUILD_TYPE) {
            "debug" -> {
                assertEquals("com.lipengzhou.webtvlive.debug", BuildConfig.APPLICATION_ID)
                assertTrue(BuildConfig.VERSION_NAME.endsWith("-debug"))
            }
            "release" -> {
                assertEquals("com.lipengzhou.webtvlive", BuildConfig.APPLICATION_ID)
                assertFalse(BuildConfig.VERSION_NAME.endsWith("-debug"))
            }
            else -> error("Unexpected build type: ${BuildConfig.BUILD_TYPE}")
        }
    }

    @Test
    fun appUpdatesAreEnabledOnlyForRelease() {
        assertEquals(BuildConfig.BUILD_TYPE == "release", BuildConfig.APP_UPDATES_ENABLED)
    }
}
