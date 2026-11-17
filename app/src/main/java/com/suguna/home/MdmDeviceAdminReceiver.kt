package com.suguna.home

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Device admin component. Lets the admin grant Suguna Home admin rights so it can lock the
 * screen and (when provisioned as Device Owner over ADB) run as a full kiosk.
 */
class MdmDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        Log.w(TAG, "Device admin disabled")
    }

    companion object {
        private const val TAG = "MdmDeviceAdmin"
        fun component(context: Context): ComponentName =
            ComponentName(context, MdmDeviceAdminReceiver::class.java)
    }
}
