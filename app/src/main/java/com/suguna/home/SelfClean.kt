package com.suguna.home

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Deletes the installer APK(s) this app was sideloaded from, so they don't linger in Download.
 * Best-effort: on Android 11+ it needs "All files access" (MANAGE_EXTERNAL_STORAGE); without it
 * nothing is deleted and it fails quietly. Only files named like our own installers are touched.
 */
object SelfClean {

    private const val TAG = "SelfClean"
    // Matches SureMDM.apk, SureMDM_v35.apk, SugunaAgent.apk, SugunaAdmin.apk, Suguna*.apk …
    private val NAME = Regex("^(sure|suguna).*\\.apk$", RegexOption.IGNORE_CASE)

    fun canDelete(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Delete matching installer APKs from the common download folders. Returns how many went. */
    fun deleteInstallerApks(context: Context): Int {
        if (!canDelete(context)) return 0
        var removed = 0
        for (dir in candidateDirs(context)) {
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

    private fun candidateDirs(context: Context): List<File> {
        val dirs = ArrayList<File>()
        try { dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) }
        catch (e: Exception) { /* ignore */ }
        try { Environment.getExternalStorageDirectory()?.let { dirs.add(File(it, "Download")) } }
        catch (e: Exception) { /* ignore */ }
        return dirs.filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }
    }
}
