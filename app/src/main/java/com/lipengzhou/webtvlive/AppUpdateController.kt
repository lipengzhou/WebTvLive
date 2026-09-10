package com.lipengzhou.webtvlive

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri

/** Owns the complete update check, download recovery, verification and installer UI flow. */
class AppUpdateController(
    private val activity: MainActivity,
    private val panelsVisible: () -> Boolean,
    private val onManualUpdateAvailable: () -> Unit,
    private val onManualCheckFinished: () -> Unit,
) {
    private val manager = AppUpdateManager(activity)
    private val checkCoordinator = UpdateCheckCoordinator()
    private val handler = Handler(Looper.getMainLooper())
    private var updateDialog: AlertDialog? = null
    private var pendingUpdate: AppUpdateManager.CheckResult.Available? = null
    private var pendingInstall: AppUpdateManager.PendingResult.Ready? = null
    private var pendingDownloadInspected = false
    private var waitingForInstallPermission = false
    private var closed = false

    private val automaticCheck = Runnable(::startAutomaticCheck)

    val isDialogShowing: Boolean
        get() = updateDialog?.isShowing == true

    private val installPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        waitingForInstallPermission = false
        val ready = pendingInstall ?: return@registerForActivityResult
        if (canInstallPackages()) {
            launchPackageInstaller(ready)
        } else {
            pendingInstall = null
            Toast.makeText(
                activity,
                R.string.update_install_permission_denied,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE || closed) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            manager.handleDownloadComplete(downloadId, ::handlePendingDownload)
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            activity,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        handler.postDelayed(automaticCheck, AUTOMATIC_CHECK_DELAY_MS)
    }

    fun startAutomaticCheck() {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        handler.removeCallbacks(automaticCheck)
        val request = checkCoordinator.requestAutomatic()
        if (request is UpdateCheckCoordinator.RequestResult.Start) runCheck(request.mode)
    }

    fun performManualCheck() {
        if (closed) return
        when (val request = checkCoordinator.requestManual()) {
            UpdateCheckCoordinator.RequestResult.Ignored,
            UpdateCheckCoordinator.RequestResult.Queued -> {
                Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
            }
            is UpdateCheckCoordinator.RequestResult.Start -> {
                Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
                runCheck(request.mode)
            }
        }
    }

    fun onResume() {
        if (closed) return
        if (!pendingDownloadInspected) {
            pendingDownloadInspected = true
            manager.inspectPending(::handlePendingDownload)
        }
        handler.post(::showDeferredUi)
    }

    fun onWindowFocusChanged() {
        if (!closed) showDeferredUi()
    }

    fun onPanelsClosed() {
        if (!closed) handler.post(::showDeferredUi)
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        updateDialog?.dismiss()
        activity.unregisterReceiver(downloadCompleteReceiver)
        manager.close()
    }

    private fun runCheck(mode: UpdateCheckCoordinator.Mode) {
        manager.check(manual = mode == UpdateCheckCoordinator.Mode.MANUAL) { result ->
            val completion = checkCoordinator.complete(mode)
            if (closed || activity.isFinishing || activity.isDestroyed) return@check
            when (completion) {
                UpdateCheckCoordinator.Completion.StartQueuedManual -> {
                    runCheck(UpdateCheckCoordinator.Mode.MANUAL)
                }
                UpdateCheckCoordinator.Completion.Stale -> Unit
                is UpdateCheckCoordinator.Completion.Deliver -> {
                    if (mode == UpdateCheckCoordinator.Mode.AUTOMATIC) {
                        handleAutomaticResult(result)
                    } else {
                        handleManualResult(result)
                    }
                }
            }
        }
    }

    private fun handleAutomaticResult(result: AppUpdateManager.CheckResult) {
        when (result) {
            is AppUpdateManager.CheckResult.Available -> {
                pendingUpdate = result
                maybeShowPendingUpdate()
            }
            AppUpdateManager.CheckResult.UpToDate ->
                Log.i(TAG, "Automatic update check: current version is latest")
            AppUpdateManager.CheckResult.Skipped ->
                Log.i(TAG, "Automatic update check: latest version was skipped")
            is AppUpdateManager.CheckResult.Failed ->
                Log.w(TAG, "Automatic update check failed: ${result.message}")
        }
    }

    private fun handleManualResult(result: AppUpdateManager.CheckResult) {
        when (result) {
            is AppUpdateManager.CheckResult.Available -> {
                pendingUpdate = result
                onManualUpdateAvailable()
                maybeShowPendingUpdate()
            }
            AppUpdateManager.CheckResult.UpToDate -> {
                Toast.makeText(activity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                onManualCheckFinished()
            }
            AppUpdateManager.CheckResult.Skipped -> onManualCheckFinished()
            is AppUpdateManager.CheckResult.Failed -> {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_check_failed, result.message),
                    Toast.LENGTH_LONG,
                ).show()
                onManualCheckFinished()
            }
        }
    }

    private fun showDeferredUi() {
        maybeShowPendingUpdate()
        maybeInstallPendingUpdate()
    }

    private fun maybeShowPendingUpdate() {
        val available = pendingUpdate ?: return
        if (
            updateDialog?.isShowing == true || panelsVisible() ||
            !activity.window.decorView.hasWindowFocus() || activity.isFinishing || activity.isDestroyed
        ) return
        pendingUpdate = null
        showUpdateDialog(available)
    }

    private fun showUpdateDialog(available: AppUpdateManager.CheckResult.Available) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_dialog_title, available.manifest.versionName))
            .setMessage(
                activity.getString(
                    R.string.update_dialog_message,
                    packageVersionName(),
                    available.manifest.versionName,
                    available.manifest.releaseNotes,
                ),
            )
            .setNegativeButton(R.string.update_skip_version) { _, _ ->
                manager.skipVersion(available.manifest.versionCode)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_skipped, available.manifest.versionName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setPositiveButton(R.string.update_now) { _, _ -> startDownload(available) }
            .setOnDismissListener { updateDialog = null }
            .create()
        updateDialog = dialog
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
        dialog.show()
    }

    private fun startDownload(available: AppUpdateManager.CheckResult.Available) {
        when (val result = manager.enqueue(available.manifest, available.asset)) {
            is AppUpdateManager.EnqueueResult.Started -> Toast.makeText(
                activity,
                activity.getString(R.string.update_downloading, available.manifest.versionName),
                Toast.LENGTH_LONG,
            ).show()
            AppUpdateManager.EnqueueResult.AlreadyRunning -> {
                manager.allowInstallRetry()
                manager.inspectPending(::handlePendingDownload)
            }
            is AppUpdateManager.EnqueueResult.Failed -> Toast.makeText(
                activity,
                activity.getString(R.string.update_download_failed, result.message),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun handlePendingDownload(result: AppUpdateManager.PendingResult) {
        if (closed || activity.isFinishing || activity.isDestroyed) return
        when (result) {
            AppUpdateManager.PendingResult.None -> Unit
            AppUpdateManager.PendingResult.Running ->
                Toast.makeText(activity, R.string.update_download_running, Toast.LENGTH_SHORT).show()
            is AppUpdateManager.PendingResult.Ready -> {
                if (manager.wasInstallPrompted(result.task.downloadId)) return
                pendingInstall = result
                maybeInstallPendingUpdate()
            }
            is AppUpdateManager.PendingResult.Failed -> Toast.makeText(
                activity,
                activity.getString(R.string.update_verify_failed, result.message),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun maybeInstallPendingUpdate() {
        val ready = pendingInstall ?: return
        if (!activity.window.decorView.hasWindowFocus() || activity.isFinishing || activity.isDestroyed) {
            return
        }
        if (!canInstallPackages()) {
            if (waitingForInstallPermission) return
            waitingForInstallPermission = true
            manager.markInstallPrompted(ready.task.downloadId)
            Toast.makeText(activity, R.string.update_allow_unknown_sources, Toast.LENGTH_LONG).show()
            launchInstallPermissionSettings()
            return
        }
        launchPackageInstaller(ready)
    }

    private fun canInstallPackages(): Boolean = activity.packageManager.canRequestPackageInstalls()

    private fun launchInstallPermissionSettings() {
        val appSpecificIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${activity.packageName}".toUri(),
        )
        try {
            installPermissionLauncher.launch(appSpecificIntent)
        } catch (firstError: ActivityNotFoundException) {
            Log.w(TAG, "App-specific unknown sources settings unavailable", firstError)
            try {
                installPermissionLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (secondError: ActivityNotFoundException) {
                waitingForInstallPermission = false
                pendingInstall = null
                Toast.makeText(
                    activity,
                    R.string.update_install_permission_denied,
                    Toast.LENGTH_LONG,
                ).show()
                Log.e(TAG, "No unknown sources settings available", secondError)
            }
        }
    }

    private fun launchPackageInstaller(ready: AppUpdateManager.PendingResult.Ready) {
        val contentUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.update-files",
            ready.file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            manager.markInstallPrompted(ready.task.downloadId)
            pendingInstall = null
            activity.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            manager.allowInstallRetry()
            Toast.makeText(activity, R.string.update_no_installer, Toast.LENGTH_LONG).show()
            Log.e(TAG, "No package installer available", error)
        }
    }

    private fun packageVersionName(): String =
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
            ?: activity.getString(R.string.about_version_unknown)

    private companion object {
        const val TAG = "WebTvLive"
        const val AUTOMATIC_CHECK_DELAY_MS = 15_000L
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
