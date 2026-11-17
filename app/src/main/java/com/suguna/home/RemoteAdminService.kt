package com.suguna.home

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote-admin agent. Runs as a foreground service and every POLL_MS:
 *   1. writes this device's status to  /devices/<id>   (name, enrollment, model, battery, lastSeen)
 *   2. reads /devices/<id>/command  and, if present, executes it and deletes it.
 *
 * Commands (sent by the Suguna Admin app):
 *   "lock"           -> lock the screen now
 *   "factory_reset"  -> wipe the device (reuses Kiosk.factoryReset)
 *
 * Firebase Realtime Database over REST — no Firebase SDK. The DB URL + secret come from
 * BuildConfig (injected at build time), so the secret never lives in the source.
 */
class RemoteAdminService : Service() {

    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (BuildConfig.FIREBASE_DB_URL.isBlank() || BuildConfig.FIREBASE_DB_SECRET.isBlank()) {
            Log.w(TAG, "No Firebase config baked in; agent idle.")
            return START_STICKY
        }
        if (!running) {
            running = true
            Thread { loop() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun base() = BuildConfig.FIREBASE_DB_URL.trimEnd('/')
    private fun secret() = BuildConfig.FIREBASE_DB_SECRET

    private fun loop() {
        while (running) {
            try {
                checkIn()
                pollCommand()
            } catch (e: Exception) {
                Log.w(TAG, "cycle failed: ${e.message}")
            }
            try { Thread.sleep(POLL_MS) } catch (e: InterruptedException) { break }
        }
    }

    private fun deviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun checkIn() {
        val prefs = Prefs(this)
        val body = JSONObject()
            .put("name", prefs.studentName)
            .put("enrollment", prefs.enrollmentNumber)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("appVersion", appVersion())
            .put("battery", batteryPct())
            .put("lastSeen", System.currentTimeMillis())
            .toString()
        httpWrite("PATCH", "${base()}/devices/${deviceId()}.json?auth=${secret()}", body)
    }

    private fun pollCommand() {
        val txt = httpRead("${base()}/devices/${deviceId()}/command.json?auth=${secret()}") ?: return
        if (txt.isBlank() || txt == "null") return
        val action = try { JSONObject(txt).optString("action") } catch (e: Exception) { "" }
        if (action.isBlank()) return
        Log.i(TAG, "command: $action")
        // Clear it first so it runs once even if the action restarts/wipes the device.
        httpWrite("DELETE", "${base()}/devices/${deviceId()}/command.json?auth=${secret()}", null)
        when (action) {
            "lock" -> lockNow()
            "factory_reset" -> Kiosk.factoryReset(this)
        }
    }

    private fun lockNow() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dpm.lockNow()
        } catch (e: Exception) {
            Log.w(TAG, "lockNow failed: ${e.message}")
        }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun batteryPct(): Int = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) { -1 }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Device management", NotificationManager.IMPORTANCE_MIN)
                )
            }
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Managed device")
            .setContentText("Monitoring is active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    private fun httpRead(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 15000; c.readTimeout = 20000
        val ok = c.responseCode in 200..299
        val t = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect(); if (ok) t else null
    } catch (e: Exception) { Log.w(TAG, "read failed: ${e.message}"); null }

    private fun httpWrite(method: String, url: String, body: String?): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 20000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val ok = c.responseCode in 200..299
        c.disconnect(); ok
    } catch (e: Exception) { Log.w(TAG, "write failed: ${e.message}"); false }

    companion object {
        private const val TAG = "RemoteAdmin"
        private const val CHANNEL = "device_mgmt"
        private const val NOTIF_ID = 42
        private const val POLL_MS = 45_000L

        /** Start the agent (from LauncherActivity and on boot). */
        fun start(context: Context) {
            val i = Intent(context, RemoteAdminService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
