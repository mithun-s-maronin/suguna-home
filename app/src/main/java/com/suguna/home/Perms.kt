package com.suguna.home

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** The runtime permissions v36 added (location, usage access, overlay) and their state. */
object Perms {

    fun hasLocation(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(c: Context): Boolean = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    fun hasOverlay(c: Context): Boolean = Settings.canDrawOverlays(c)

    /** True only once every new permission is in place (used to decide whether to show the gate). */
    fun allGranted(c: Context): Boolean = hasLocation(c) && hasUsageAccess(c) && hasOverlay(c)
}
