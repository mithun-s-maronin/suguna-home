package com.suguna.home

import android.os.Build
import android.os.Environment
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remote file-browser operations, driven by commands from the admin panel. Results go back through
 * the Realtime Database (no Firebase Storage → free plan). File transfers are base64 in a DB node,
 * so they're capped small; big files would need Blaze + Storage.
 *
 * Browsing shared storage (/sdcard, Download, …) on Android 11+ needs "All files access"
 * (MANAGE_EXTERNAL_STORAGE), granted once per tablet.
 */
object FileOps {

    /** Base64 in a single DB node — keep transfers well under RTDB write limits. */
    const val MAX_TRANSFER = 3 * 1024 * 1024   // 3 MB raw

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun defaultRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    /** Run one file command and return the JSON result to publish at /devices/<uid>/fileResult. */
    fun handle(cmd: JSONObject): JSONObject {
        val action = cmd.optString("action")
        val path = cmd.optString("path")
        val res = JSONObject()
            .put("reqId", cmd.optString("reqId"))
            .put("action", action)
            .put("ts", System.currentTimeMillis())
        if (!hasAccess()) {
            return res.put("ok", false)
                .put("error", "This tablet hasn't granted \"All files access\" yet")
                .put("needsAccess", true)
        }
        return try {
            when (action) {
                "list_dir" -> listDir(path, res)
                "read_file" -> readFile(path, res)
                "delete_file" -> deleteFile(path, res)
                "write_file" -> writeFile(path, cmd.optString("b64"), res)
                else -> res.put("ok", false).put("error", "unknown action")
            }
        } catch (e: Exception) {
            res.put("ok", false).put("error", e.message ?: "error")
        }
    }

    private fun listDir(path: String, res: JSONObject): JSONObject {
        val dir = File(path.ifBlank { defaultRoot() })
        if (!dir.exists()) return res.put("ok", false).put("error", "not found")
        if (!dir.isDirectory) return res.put("ok", false).put("error", "not a directory")
        val entries = JSONArray()
        val listing = dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
        for (f in listing.take(2000)) {
            entries.put(
                JSONObject()
                    .put("n", f.name)
                    .put("d", f.isDirectory)
                    .put("s", if (f.isFile) f.length() else 0L),
            )
        }
        return res.put("ok", true).put("path", dir.absolutePath).put("entries", entries)
    }

    private fun readFile(path: String, res: JSONObject): JSONObject {
        val f = File(path)
        if (!f.isFile) return res.put("ok", false).put("error", "not a file")
        if (f.length() > MAX_TRANSFER) {
            return res.put("ok", false)
                .put("error", "too big (${f.length() / 1024 / 1024} MB; 3 MB max on the free plan)")
        }
        return res.put("ok", true)
            .put("name", f.name)
            .put("size", f.length())
            .put("b64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
    }

    private fun deleteFile(path: String, res: JSONObject): JSONObject {
        val f = File(path)
        if (!f.exists()) return res.put("ok", false).put("error", "not found")
        val ok = f.deleteRecursively()
        return if (ok) res.put("ok", true) else res.put("ok", false).put("error", "delete failed")
    }

    private fun writeFile(path: String, b64: String, res: JSONObject): JSONObject {
        if (b64.isBlank()) return res.put("ok", false).put("error", "no data")
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return res.put("ok", true).put("name", f.name).put("size", bytes.size)
    }
}
