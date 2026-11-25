package com.suguna.home

import android.app.AppOpsManager
import android.content.Context

/** Usage-access permission (for the current-app field reported to the panel) and its state. */
object Perms {

    fun hasUsageAccess(c: Context): Boolean = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    /** True once the gate's permission is in place — only usage access is needed. */
    fun allGranted(c: Context): Boolean = hasUsageAccess(c)
}
