package com.suguna.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the result of a silent PackageInstaller session (used by AppInstaller).
 * Log-only, like the original — no extra pop-ups beyond the "Installing …" toast.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "app"
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Shouldn't happen for a Device Owner, but if it does, show the system confirm.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    if (confirm != null) context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.w(TAG, "confirm intent failed: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Installation succeeded for $pkg")
            else -> Log.e(TAG, "Installation failed ($status): " +
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    private companion object {
        const val TAG = "InstallResult"
    }
}
