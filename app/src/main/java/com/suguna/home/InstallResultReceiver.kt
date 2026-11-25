package com.suguna.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the outcome of a silent PackageInstaller session (see ApkInstaller) and records it in
 * Prefs.lastInstall, which the next check-in reports to the panel.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "app"
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val prefs = Prefs(context)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> prefs.lastInstall = "$label installed ✓"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // With Device Owner this shouldn't occur, but if it does, surface the confirm UI.
                val confirm = @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (e: Exception) { Log.w(TAG, "confirm: ${e.message}") }
                }
                prefs.lastInstall = "$label: awaiting confirmation"
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $status"
                prefs.lastInstall = "$label failed: $msg"
            }
        }
        Log.i(TAG, "install result: ${prefs.lastInstall}")
    }

    companion object {
        private const val TAG = "InstallResult"
        const val EXTRA_LABEL = "label"
    }
}
