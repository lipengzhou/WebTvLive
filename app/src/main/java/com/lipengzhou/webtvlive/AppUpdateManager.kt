package com.lipengzhou.webtvlive

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class AppUpdateManager(context: Context) {

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data object Skipped : CheckResult
        data class Available(val manifest: UpdateManifest, val asset: UpdateAsset) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    sealed interface EnqueueResult {
        data class Started(val downloadId: Long) : EnqueueResult
        data object AlreadyRunning : EnqueueResult
        data class Failed(val message: String) : EnqueueResult
    }

    data class DownloadTask(
        val downloadId: Long,
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    sealed interface PendingResult {
        data object None : PendingResult
        data object Running : PendingResult
        data class Ready(val file: File, val task: DownloadTask) : PendingResult
        data class Failed(val message: String) : PendingResult
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    val currentVersionCode: Long
        get() = installedPackageInfo().longVersionCode

    val currentChannel: String
        get() = "${BuildConfig.UPDATE_ENGINE}-${if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"}"

    fun check(manual: Boolean, callback: (CheckResult) -> Unit) {
        executor.execute {
            val result = runCatching {
                val manifest = fetchManifest()
                val decision = UpdatePolicy.decide(
                    currentVersionCode = currentVersionCode,
                    latestVersionCode = manifest.versionCode,
                    skippedVersionCode = prefs.getLong(KEY_SKIPPED_VERSION, -1L),
                    manual = manual,
                )
                if (manifest.versionCode <= currentVersionCode) {
                    clearObsoleteSkip()
                }
                when (decision) {
                    UpdateDecision.UP_TO_DATE -> CheckResult.UpToDate
                    UpdateDecision.SKIPPED -> CheckResult.Skipped
                    UpdateDecision.AVAILABLE -> {
                        val asset = manifest.assetFor(
                            BuildConfig.UPDATE_ENGINE,
                            if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a",
                        ) ?: error("更新清单缺少当前安装通道：$currentChannel")
                        CheckResult.Available(manifest, asset)
                    }
                }
            }.getOrElse { error ->
                CheckResult.Failed(error.message ?: "检查更新失败")
            }
            mainHandler.post { callback(result) }
        }
    }

    fun skipVersion(versionCode: Long) {
        if (loadTask()?.versionCode == versionCode) clearTask(deleteFile = true)
        prefs.edit { putLong(KEY_SKIPPED_VERSION, versionCode) }
    }

    fun enqueue(manifest: UpdateManifest, asset: UpdateAsset): EnqueueResult {
        val existing = loadTask()
        if (
            existing != null &&
            existing.versionCode == manifest.versionCode &&
            existing.url == asset.url &&
            existing.sizeBytes == asset.sizeBytes &&
            existing.sha256 == asset.sha256
        ) {
            val state = queryStatus(existing.downloadId)
            if (state == DownloadManager.STATUS_PENDING ||
                state == DownloadManager.STATUS_RUNNING ||
                state == DownloadManager.STATUS_PAUSED ||
                state == DownloadManager.STATUS_SUCCESSFUL
            ) {
                return EnqueueResult.AlreadyRunning
            }
        }

        clearTask(deleteFile = true)
        val updatesDir = updatesDirectory() ?: return EnqueueResult.Failed("无法创建更新下载目录")
        if (!updatesDir.exists() && !updatesDir.mkdirs()) {
            return EnqueueResult.Failed("无法创建更新下载目录")
        }
        val destination = File(updatesDir, asset.fileName)
        destination.delete()

        return runCatching {
            val request = DownloadManager.Request(asset.url.toUri())
                .setTitle("看电视 ${manifest.versionName}")
                .setDescription("正在下载应用更新")
                .setMimeType(APK_MIME_TYPE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setDestinationInExternalFilesDir(
                    appContext,
                    Environment.DIRECTORY_DOWNLOADS,
                    "updates/${asset.fileName}",
                )
            val downloadId = downloadManager.enqueue(request)
            saveTask(
                DownloadTask(
                    downloadId = downloadId,
                    versionCode = manifest.versionCode,
                    versionName = manifest.versionName,
                    url = asset.url,
                    fileName = asset.fileName,
                    sizeBytes = asset.sizeBytes,
                    sha256 = asset.sha256,
                ),
            )
            EnqueueResult.Started(downloadId)
        }.getOrElse { error ->
            EnqueueResult.Failed(error.message ?: "无法启动更新下载")
        }
    }

    fun inspectPending(callback: (PendingResult) -> Unit) {
        val task = loadTask() ?: run {
            callback(PendingResult.None)
            return
        }
        if (task.versionCode <= currentVersionCode) {
            clearTask(deleteFile = true)
            callback(PendingResult.None)
            return
        }
        when (queryStatus(task.downloadId)) {
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED -> callback(PendingResult.Running)
            DownloadManager.STATUS_SUCCESSFUL -> verifyDownloadedTask(task, callback)
            DownloadManager.STATUS_FAILED, null -> {
                clearTask(deleteFile = true)
                callback(PendingResult.Failed("更新下载失败，请重新检查更新"))
            }
            else -> {
                clearTask(deleteFile = true)
                callback(PendingResult.Failed("更新下载状态异常，请重新检查更新"))
            }
        }
    }

    fun handleDownloadComplete(downloadId: Long, callback: (PendingResult) -> Unit) {
        val task = loadTask() ?: return
        if (task.downloadId == downloadId) inspectPending(callback)
    }

    fun wasInstallPrompted(downloadId: Long): Boolean =
        prefs.getLong(KEY_INSTALL_PROMPTED_DOWNLOAD_ID, -1L) == downloadId

    fun markInstallPrompted(downloadId: Long) {
        prefs.edit { putLong(KEY_INSTALL_PROMPTED_DOWNLOAD_ID, downloadId) }
    }

    fun allowInstallRetry() {
        prefs.edit { remove(KEY_INSTALL_PROMPTED_DOWNLOAD_ID) }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun fetchManifest(): UpdateManifest {
        val connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "WebTvLive/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            require(status in 200..299) { "更新服务器返回 HTTP $status" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (output.size() <= MAX_MANIFEST_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            require(bytes.size <= MAX_MANIFEST_BYTES) { "更新清单过大" }
            UpdateManifestParser.parse(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun verifyDownloadedTask(
        task: DownloadTask,
        callback: (PendingResult) -> Unit,
    ) {
        executor.execute {
            val result = runCatching {
                require(task.versionCode > currentVersionCode) { "下载的版本不高于当前版本" }
                val directory = updatesDirectory() ?: error("更新下载目录不可用")
                val file = File(directory, task.fileName)
                require(file.isFile) { "更新文件不存在" }
                require(file.length() == task.sizeBytes) { "更新文件大小校验失败" }
                require(file.sha256() == task.sha256) { "更新文件完整性校验失败" }
                verifyPackage(file, task)
                PendingResult.Ready(file, task)
            }.getOrElse { error ->
                clearTask(deleteFile = true)
                PendingResult.Failed(error.message ?: "更新文件校验失败")
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun verifyPackage(file: File, task: DownloadTask) {
        val archive = packageArchiveInfo(file) ?: error("无法读取更新安装包")
        require(archive.packageName == appContext.packageName) { "更新安装包的应用标识不一致" }
        require(archive.longVersionCode == task.versionCode) { "更新安装包版本与清单不一致" }
        require(archive.versionName == "${task.versionName}-${BuildConfig.UPDATE_ENGINE}") {
            "更新安装包与当前内核不一致"
        }
        val installedSigningInfo = installedPackageInfo().signingInfo
            ?: error("无法读取当前应用签名")
        val archiveSigningInfo = archive.signingInfo ?: error("无法读取更新安装包签名")
        val signatureMatches = UpdateSignatureMatcher.matches(
            installedCurrentSigners = installedSigningInfo.apkContentsSigners
                .map { it.toByteArray().sha256() }
                .toSet(),
            archiveCurrentSigners = archiveSigningInfo.apkContentsSigners
                .map { it.toByteArray().sha256() }
                .toSet(),
            archiveSignerHistory = archiveSigningInfo.signingCertificateHistory
                .map { it.toByteArray().sha256() }
                .toSet(),
            multipleSigners = installedSigningInfo.hasMultipleSigners() ||
                archiveSigningInfo.hasMultipleSigners(),
        )
        require(signatureMatches) { "更新安装包签名与当前应用不一致" }
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo =
        appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )

    @Suppress("DEPRECATION")
    private fun packageArchiveInfo(file: File): PackageInfo? =
        appContext.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )

    private fun queryStatus(downloadId: Long): Int? {
        return runCatching {
            val query = DownloadManager.Query().setFilterById(downloadId)
            downloadManager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        }.getOrNull()
    }

    private fun saveTask(task: DownloadTask) {
        prefs.edit {
            putLong(KEY_DOWNLOAD_ID, task.downloadId)
            putLong(KEY_DOWNLOAD_VERSION_CODE, task.versionCode)
            putString(KEY_DOWNLOAD_VERSION_NAME, task.versionName)
            putString(KEY_DOWNLOAD_URL, task.url)
            putString(KEY_DOWNLOAD_FILE_NAME, task.fileName)
            putLong(KEY_DOWNLOAD_SIZE, task.sizeBytes)
            putString(KEY_DOWNLOAD_SHA256, task.sha256)
        }
    }

    private fun loadTask(): DownloadTask? {
        val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return null
        val versionName = prefs.getString(KEY_DOWNLOAD_VERSION_NAME, null) ?: return null
        val url = prefs.getString(KEY_DOWNLOAD_URL, null) ?: return null
        val fileName = prefs.getString(KEY_DOWNLOAD_FILE_NAME, null) ?: return null
        val sha256 = prefs.getString(KEY_DOWNLOAD_SHA256, null) ?: return null
        return DownloadTask(
            downloadId = downloadId,
            versionCode = prefs.getLong(KEY_DOWNLOAD_VERSION_CODE, -1L),
            versionName = versionName,
            url = url,
            fileName = fileName,
            sizeBytes = prefs.getLong(KEY_DOWNLOAD_SIZE, -1L),
            sha256 = sha256,
        )
    }

    private fun clearTask(deleteFile: Boolean) {
        val task = loadTask()
        if (task != null) {
            downloadManager.remove(task.downloadId)
            if (deleteFile) updatesDirectory()?.resolve(task.fileName)?.delete()
        }
        prefs.edit {
            remove(KEY_DOWNLOAD_ID)
            remove(KEY_DOWNLOAD_VERSION_CODE)
            remove(KEY_DOWNLOAD_VERSION_NAME)
            remove(KEY_DOWNLOAD_URL)
            remove(KEY_DOWNLOAD_FILE_NAME)
            remove(KEY_DOWNLOAD_SIZE)
            remove(KEY_DOWNLOAD_SHA256)
            remove(KEY_INSTALL_PROMPTED_DOWNLOAD_ID)
        }
    }

    private fun clearObsoleteSkip() {
        if (prefs.getLong(KEY_SKIPPED_VERSION, -1L) <= currentVersionCode) {
            prefs.edit { remove(KEY_SKIPPED_VERSION) }
        }
    }

    private fun updatesDirectory(): File? =
        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve("updates")

    private fun File.sha256(): String = inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val MANIFEST_URL =
            "https://gitee.com/lipengzhou/WebTvLive/raw/main/release/update.json"
        private const val PREFS_NAME = "app_update_prefs"
        private const val KEY_SKIPPED_VERSION = "skipped_version_code"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_DOWNLOAD_VERSION_CODE = "download_version_code"
        private const val KEY_DOWNLOAD_VERSION_NAME = "download_version_name"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_DOWNLOAD_FILE_NAME = "download_file_name"
        private const val KEY_DOWNLOAD_SIZE = "download_size"
        private const val KEY_DOWNLOAD_SHA256 = "download_sha256"
        private const val KEY_INSTALL_PROMPTED_DOWNLOAD_ID = "install_prompted_download_id"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
