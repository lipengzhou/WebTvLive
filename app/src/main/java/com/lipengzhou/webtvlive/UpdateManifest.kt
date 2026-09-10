package com.lipengzhou.webtvlive

import org.json.JSONObject
import java.net.URI

data class UpdateAsset(
    val channel: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val fileName: String
        get() = URI(url).path.substringAfterLast('/')
}

data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String,
    val assets: Map<String, UpdateAsset>,
) {
    fun assetFor(engine: String, abi: String): UpdateAsset? = assets["$engine-$abi"]
}

enum class UpdateDecision {
    UP_TO_DATE,
    SKIPPED,
    AVAILABLE,
}

object UpdatePolicy {
    fun decide(
        currentVersionCode: Long,
        latestVersionCode: Long,
        skippedVersionCode: Long,
        manual: Boolean,
    ): UpdateDecision = when {
        latestVersionCode <= currentVersionCode -> UpdateDecision.UP_TO_DATE
        !manual && latestVersionCode == skippedVersionCode -> UpdateDecision.SKIPPED
        else -> UpdateDecision.AVAILABLE
    }
}

object UpdateManifestParser {
    private const val EXPECTED_SCHEMA_VERSION = 1
    private const val RELEASE_HOST = "gitee.com"
    private const val RELEASE_PATH_PREFIX = "/lipengzhou/WebTvLive/releases/download/"
    private val versionNamePattern = Regex("[0-9]+(?:\\.[0-9]+){2}")
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val requiredChannels = listOf(
        "gecko-arm64-v8a",
        "gecko-armeabi-v7a",
        "webview-arm64-v8a",
        "webview-armeabi-v7a",
    )

    fun parse(json: String): UpdateManifest {
        val root = JSONObject(json)
        require(root.requirePositiveLong("schemaVersion") == EXPECTED_SCHEMA_VERSION.toLong()) {
            "不支持的更新清单版本"
        }
        val versionCode = root.requirePositiveLong("versionCode")
        val versionName = root.getString("versionName").trim()
        require(versionNamePattern.matches(versionName)) { "versionName 格式无效" }
        val releaseNotes = root.getString("releaseNotes").trim()
        require(releaseNotes.isNotEmpty()) { "更新说明不能为空" }

        val assetsJson = root.getJSONObject("assets")
        val assetKeys = buildSet {
            val keys = assetsJson.keys()
            while (keys.hasNext()) add(keys.next())
        }
        require(assetKeys == requiredChannels.toSet()) { "更新清单必须且只能包含四个发布通道" }
        val assets = requiredChannels.associateWith { channel ->
            val item = assetsJson.getJSONObject(channel)
            parseAsset(channel, versionName, item)
        }
        return UpdateManifest(versionCode, versionName, releaseNotes, assets)
    }

    private fun parseAsset(
        channel: String,
        versionName: String,
        item: JSONObject,
    ): UpdateAsset {
        val url = item.getString("url").trim()
        val sizeBytes = item.requirePositiveLong("sizeBytes")
        val sha256 = item.getString("sha256").trim().lowercase()
        require(sha256Pattern.matches(sha256)) { "$channel 的 SHA-256 无效" }
        requireValidReleaseUrl(channel, versionName, url)
        return UpdateAsset(channel, url, sizeBytes, sha256)
    }

    private fun requireValidReleaseUrl(channel: String, versionName: String, url: String) {
        val uri = runCatching { URI(url) }.getOrElse {
            throw IllegalArgumentException("$channel 的下载地址无效", it)
        }
        // channel 形如 "gecko-arm64-v8a"：首段是内核，其余是 ABI。
        val engine = channel.substringBefore('-')
        val abi = channel.substringAfter('-')
        val expectedFileName = "webtvlive-$engine-$versionName-$abi-release.apk"
        val expectedPath = "${RELEASE_PATH_PREFIX}v$versionName/$expectedFileName"
        require(
            uri.scheme == "https" && uri.host == RELEASE_HOST &&
                uri.userInfo == null && uri.port == -1,
        ) {
            "$channel 必须使用 Gitee HTTPS 下载地址"
        }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.path == expectedPath) {
            "$channel 的 Gitee Release 路径无效"
        }
    }

    private fun JSONObject.requirePositiveLong(name: String): Long {
        val value = get(name)
        require(value is Int || value is Long) { "$name 必须是整数" }
        val longValue = value.toLong()
        require(longValue > 0) { "$name 必须是正整数" }
        return longValue
    }
}
