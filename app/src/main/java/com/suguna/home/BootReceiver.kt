package com.suguna.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the remote-admin agent after the device reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RemoteAdminService.start(context)
        }
    }
}
