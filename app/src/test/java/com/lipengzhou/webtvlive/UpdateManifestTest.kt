package com.lipengzhou.webtvlive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject

class UpdateManifestTest {

    @Test
    fun parseAcceptsFourGiteeReleaseAssets() {
        val manifest = UpdateManifestParser.parse(validManifest())

        assertEquals(3L, manifest.versionCode)
        assertEquals("0.0.3", manifest.versionName)
        assertNotNull(manifest.assetFor("gecko", "arm64-v8a"))
        assertEquals(
            "app-webview-armeabi-v7a-release.apk",
            manifest.assetFor("webview", "armeabi-v7a")?.fileName,
        )
    }

    @Test
    fun parseRejectsNonGiteeDownloadUrl() {
        val json = validManifest().replace(
            "https://gitee.com/lipengzhou/WebTvLive/releases/download/v0.0.3/",
            "https://example.com/",
        )

        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifestParser.parse(json)
        }
    }

    @Test
    fun parseRejectsUnexpectedReleasePath() {
        val json = validManifest().replace(
            "/releases/download/v0.0.3/app-gecko-arm64-v8a-release.apk",
            "/releases/download/v0.0.2/app-gecko-arm64-v8a-release.apk",
        )

        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifestParser.parse(json)
        }
    }

    @Test
    fun parseRejectsFractionalVersionCode() {
        val json = validManifest().replace("\"versionCode\": 3", "\"versionCode\": 3.5")

        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifestParser.parse(json)
        }
    }

    @Test
    fun parseRejectsMissingChannel() {
        val json = validManifest().replace(
            Regex(",\\s*\"webview-armeabi-v7a\"[^}]+}\\s*\\n\\s*}"),
            "\n  }",
        )

        assertThrows(Exception::class.java) {
            UpdateManifestParser.parse(json)
        }
    }

    @Test
    fun parseRejectsUnexpectedChannel() {
        val root = JSONObject(validManifest())
        root.getJSONObject("assets").put(
            "other-arm64-v8a",
            JSONObject()
                .put("url", "https://gitee.com/example.apk")
                .put("sizeBytes", 1234)
                .put("sha256", "a".repeat(64)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifestParser.parse(root.toString())
        }
    }

    @Test
    fun automaticCheckHonorsSkippedVersionButManualCheckDoesNot() {
        assertEquals(
            UpdateDecision.SKIPPED,
            UpdatePolicy.decide(2, 3, 3, manual = false),
        )
        assertEquals(
            UpdateDecision.AVAILABLE,
            UpdatePolicy.decide(2, 3, 3, manual = true),
        )
    }

    @Test
    fun currentOrOlderVersionIsUpToDate() {
        assertEquals(UpdateDecision.UP_TO_DATE, UpdatePolicy.decide(3, 3, -1, false))
        assertEquals(UpdateDecision.UP_TO_DATE, UpdatePolicy.decide(3, 2, -1, true))
    }

    private fun validManifest(): String {
        val hash = "a".repeat(64)
        fun asset(channel: String) = """
            "$channel": {
              "url": "https://gitee.com/lipengzhou/WebTvLive/releases/download/v0.0.3/app-$channel-release.apk",
              "sizeBytes": 1234,
              "sha256": "$hash"
            }
        """.trimIndent()
        return """
            {
              "schemaVersion": 1,
              "versionCode": 3,
              "versionName": "0.0.3",
              "releaseNotes": "新增自动更新",
              "assets": {
                ${asset("gecko-arm64-v8a")},
                ${asset("gecko-armeabi-v7a")},
                ${asset("webview-arm64-v8a")},
                ${asset("webview-armeabi-v7a")}
              }
            }
        """.trimIndent()
    }
}
