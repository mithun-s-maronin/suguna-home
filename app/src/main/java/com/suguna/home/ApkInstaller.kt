package com.suguna.home

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an APK from a URL and installs it silently. Because this app is Device Owner, the
 * PackageInstaller session commits with no user prompt and no "unknown sources" toggle.
 *
 * Updating an app in place requires the new APK to be signed with the same key as the installed
 * version (Android rule). A new app installs with no such restriction.
 *
 * The result is written to Prefs.lastInstall and reported to the panel on the next check-in.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    fun install(context: Context, url: String, label: String) {
        val prefs = Prefs(context)
        prefs.lastInstall = "$label: downloading…"
        val apk: File
        try {
            apk = download(context, url)
        } catch (e: Exception) {
            prefs.lastInstall = "$label: download failed (${e.message})"
            Log.w(TAG, "download failed: ${e.message}")
            return
        }
        prefs.lastInstall = "$label: installing…"
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(label, 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(InstallResultReceiver.EXTRA_LABEL, label)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            prefs.lastInstall = "$label: install error (${e.message})"
            Log.w(TAG, "install failed: ${e.message}")
        } finally {
            try { apk.delete() } catch (_: Exception) {}
        }
    }

    private fun download(context: Context, url: String): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.instanceFollowRedirects = true
        conn.connect()
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP ${conn.responseCode}")
        }
        val file = File(context.cacheDir, "update_${System.currentTimeMillis()}.apk")
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        if (file.length() < 1024) throw IOException("downloaded file too small")
        return file
    }
}
