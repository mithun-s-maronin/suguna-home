package com.suguna.home

import android.app.Activity
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * Equivalent of the original AppInstallerManager.installIfNewer(): downloads an APK and,
 * if it is newer than what is installed (or not installed yet), opens the Android installer.
 *
 * The original installed silently because it was Device Owner; a normal app has to let
 * the student tap "Install" / "Update" in the system dialog.
 *
 * Call [release] from the owning Activity's onDestroy.
 */
class AppInstaller(private val activity: Activity) {

    private val downloadManager =
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** downloadId → expected package name */
    private val activeDownloads = mutableMapOf<Long, String>()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val pkg = activeDownloads.remove(id) ?: return
            onDownloadFinished(id, pkg)
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Sent by the system download service, so the receiver must be exported.
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(downloadReceiver, filter)
        }
    }

    /** Returns false if it could not start (e.g. install permission missing). */
    fun installIfNewer(url: String, packageName: String): Boolean {
        if (activeDownloads.containsValue(packageName)) return true
        // As Device Owner the app installs silently (no "unknown sources" needed). Only a normal
        // install needs that permission.
        if (!Kiosk.isDeviceOwner(activity) && !activity.packageManager.canRequestPackageInstalls()) {
            toast("Allow Sure MDM to install apps, then try again.")
            SetupPermissions.openInstallUnknownApps(activity)
            return false
        }

        val fileName = "$packageName.apk"
        apkFile(fileName)?.delete() // avoid DownloadManager saving "name-1.apk"

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)

        return try {
            activeDownloads[downloadManager.enqueue(request)] = packageName
            true
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
            false
        }
    }

    /** Deletes downloaded APKs (part of "Clear Downloads"). Returns how many files were removed. */
    fun clearDownloadedApks(): Int {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return 0
        return dir.listFiles()?.count { it.delete() } ?: 0
    }

    private fun apkFile(name: String): File? =
        activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, name) }

    @Suppress("DEPRECATION")
    private fun onDownloadFinished(id: Long, packageName: String) {
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(id))
        val succeeded = cursor?.use { c ->
            c.moveToFirst() &&
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                DownloadManager.STATUS_SUCCESSFUL
        } ?: false
        if (!succeeded) {
            toast("Download failed for $packageName")
            return
        }

        // Compare versions like the original installIfNewer(), using the same toast wording.
        val file = apkFile("$packageName.apk")
        val apkInfo = file?.let { activity.packageManager.getPackageArchiveInfo(it.absolutePath, 0) }
        var label = packageName
        var versionName = "?"
        if (apkInfo != null) {
            apkInfo.applicationInfo?.let {
                it.sourceDir = file.absolutePath
                it.publicSourceDir = file.absolutePath
                label = try {
                    it.loadLabel(activity.packageManager).toString()
                } catch (e: Exception) {
                    packageName
                }
            }
            versionName = apkInfo.versionName ?: "?"
            val installedCode = try {
                versionCodeOf(activity.packageManager.getPackageInfo(apkInfo.packageName, 0))
            } catch (e: PackageManager.NameNotFoundException) {
                -1L
            }
            if (installedCode >= 0 && installedCode >= versionCodeOf(apkInfo)) {
                toast("$label is already up to date (v$versionName)")
                file.delete()
                return
            }
        }

        toast("Installing $label v$versionName…")

        // Device Owner → install silently; otherwise open the system installer dialog.
        if (Kiosk.isDeviceOwner(activity) && file != null) {
            silentInstall(file, packageName)
            return
        }

        val apkUri = downloadManager.getUriForDownloadedFile(id)
        if (apkUri == null) {
            toast("Couldn't open the downloaded file for $packageName")
            return
        }
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(installIntent)
        } catch (e: ActivityNotFoundException) {
            toast("No installer found on this device.")
        }
    }

    /** Silent install via PackageInstaller — works without any prompt for a Device Owner. */
    private fun silentInstall(apk: File, packageName: String) {
        try {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply { setAppPackageName(packageName) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val statusIntent = Intent(activity, InstallResultReceiver::class.java)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(activity, sessionId, statusIntent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            Log.e(TAG, "silentInstall failed", e)
            toast("Install failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    fun release() {
        try {
            activity.unregisterReceiver(downloadReceiver)
        } catch (e: IllegalArgumentException) {
            // already unregistered
        }
    }

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val TAG = "AppInstaller"
    }
}
