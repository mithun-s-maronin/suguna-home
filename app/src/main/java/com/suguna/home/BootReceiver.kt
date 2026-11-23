package com.suguna.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the remote-admin agent after the device reboots, and — crucially — right after the app
 * updates itself (MY_PACKAGE_REPLACED). An update kills the running service, and without this the
 * tablet would sit "offline" in the panel until someone reopened the MDM launcher.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> RemoteAdminService.start(context)
        }
    }
}
