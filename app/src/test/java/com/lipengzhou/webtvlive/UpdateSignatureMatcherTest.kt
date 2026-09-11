package com.lipengzhou.webtvlive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSignatureMatcherTest {

    @Test
    fun singleSignerAcceptsWhenArchiveHistoryCoversInstalledSigner() {
        assertTrue(
            UpdateSignatureMatcher.matches(
                installedCurrentSigners = setOf(SIGNER_A),
                archiveCurrentSigners = setOf(SIGNER_A),
                archiveSignerHistory = setOf(SIGNER_A),
                multipleSigners = false,
            ),
        )
    }

    @Test
    fun singleSignerAcceptsRotatedArchiveWhoseHistoryStillIncludesInstalledSigner() {
        // App was signed by A; the update rotated to B but keeps A in its signer history.
        assertTrue(
            UpdateSignatureMatcher.matches(
                installedCurrentSigners = setOf(SIGNER_A),
                archiveCurrentSigners = setOf(SIGNER_B),
                archiveSignerHistory = setOf(SIGNER_A, SIGNER_B),
                multipleSigners = false,
            ),
        )
    }

    @Test
    fun singleSignerRejectsWhenArchiveHistoryMissesInstalledSigner() {
        assertFalse(
            UpdateSignatureMatcher.matches(
                installedCurrentSigners = setOf(SIGNER_A),
                archiveCurrentSigners = setOf(SIGNER_B),
                archiveSignerHistory = setOf(SIGNER_B),
                multipleSigners = false,
            ),
        )
    }

    @Test
    fun multipleSignersRequireExactCurrentSignerSetEquality() {
        assertTrue(
            UpdateSignatureMatcher.matches(
                installedCurrentSigners = setOf(SIGNER_A, SIGNER_B),
                archiveCurrentSigners = setOf(SIGNER_B, SIGNER_A),
                archiveSignerHistory = emptySet(),
                multipleSigners = true,
            ),
        )
    }

    @Test
    fun multipleSignersRejectWhenCurrentSignerSetsDiffer() {
        assertFalse(
            UpdateSignatureMatcher.matches(
                installedCurrentSigners = setOf(SIGNER_A, SIGNER_B),
                archiveCurrentSigners = setOf(SIGNER_A),
                archiveSignerHistory = setOf(SIGNER_A, SIGNER_B),
                multipleSigners = true,
            ),
        )
    }

    @Test
    fun multipleSignersIgnoreHistoryContainment() {
        // History covering the installed signer must not rescue a multi-signer set mismatch.
        assertFalse(
            UpdateSignatureMatcher.matches(
                installedCurrentSigners = setOf(SIGNER_A),
                archiveCurrentSigners = setOf(SIGNER_A, SIGNER_B),
                archiveSignerHistory = setOf(SIGNER_A),
                multipleSigners = true,
            ),
        )
    }

    @Test
    fun emptyInstalledSignersNeverMatch() {
        assertFalse(
            UpdateSignatureMatcher.matches(
                installedCurrentSigners = emptySet(),
                archiveCurrentSigners = emptySet(),
                archiveSignerHistory = emptySet(),
                multipleSigners = false,
            ),
        )
        assertFalse(
            UpdateSignatureMatcher.matches(
                installedCurrentSigners = emptySet(),
                archiveCurrentSigners = emptySet(),
                archiveSignerHistory = emptySet(),
                multipleSigners = true,
            ),
        )
    }

    private companion object {
        val SIGNER_A = "a".repeat(64)
        val SIGNER_B = "b".repeat(64)
    }
}
