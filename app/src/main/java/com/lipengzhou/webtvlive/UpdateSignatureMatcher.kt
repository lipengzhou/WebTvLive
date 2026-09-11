package com.lipengzhou.webtvlive

/**
 * Decides whether an update APK's signing certificates match the currently installed app.
 *
 * Pure and free of Android types so it can be unit-tested: [AppUpdateManager] extracts the SHA-256
 * digests of each side's signers and hands them here. This is a security-critical gate — a bad
 * match must never allow a foreign-signed APK to be treated as a legitimate update.
 */
object UpdateSignatureMatcher {
    /**
     * @param installedCurrentSigners SHA-256 digests of the installed app's current signers
     *   (`apkContentsSigners`).
     * @param archiveCurrentSigners SHA-256 digests of the update APK's current signers.
     * @param archiveSignerHistory SHA-256 digests of the update APK's signer history
     *   (`signingCertificateHistory`); only consulted in the single-signer case.
     * @param multipleSigners true when either side reports multiple signers, forcing the stricter
     *   "current signer sets must be equal" rule instead of history containment.
     */
    fun matches(
        installedCurrentSigners: Set<String>,
        archiveCurrentSigners: Set<String>,
        archiveSignerHistory: Set<String>,
        multipleSigners: Boolean,
    ): Boolean {
        if (installedCurrentSigners.isEmpty()) return false
        return if (multipleSigners) {
            // Signer-rotation history is undefined for multi-signer APKs; require an exact match.
            installedCurrentSigners == archiveCurrentSigners
        } else {
            // Single signer may have rotated: the archive's history must cover the installed signer.
            archiveSignerHistory.containsAll(installedCurrentSigners)
        }
    }
}
