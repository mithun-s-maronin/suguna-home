package com.suguna.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
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
 * Companion agent service. Runs as a device ADMIN (not owner), so it can be added alongside the
 * existing fork without a reset. Every POLL_MS it checks in to Firebase and runs commands:
 *   "lock"          -> dpm.lockNow()
 *   "factory_reset" -> dpm.wipeData(0)  (best-effort; may be blocked on Android 14 without Device Owner)
 */
class AgentService : Service() {

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

    override fun onDestroy() { running = false; super.onDestroy() }

    private fun base() = BuildConfig.FIREBASE_DB_URL.trimEnd('/')
    private fun secret() = BuildConfig.FIREBASE_DB_SECRET
    private fun dpm() = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun loop() {
        while (running) {
            try { checkIn(); pollCommand() } catch (e: Exception) { Log.w(TAG, "cycle: ${e.message}") }
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
            .put("appVersion", "agent")
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
        httpWrite("DELETE", "${base()}/devices/${deviceId()}/command.json?auth=${secret()}", null)
        val admin = AgentAdminReceiver.component(this)
        if (!dpm().isAdminActive(admin)) { Log.w(TAG, "not an active admin; ignoring $action"); return }
        when (action) {
            "lock" -> try { dpm().lockNow() } catch (e: Exception) { Log.w(TAG, "lock: ${e.message}") }
            "factory_reset" -> try {
                dpm().wipeData(0)
            } catch (e: Exception) {
                Log.w(TAG, "wipeData failed (expected without Device Owner on A14): ${e.message}")
            }
        }
    }

    private fun batteryPct(): Int = try {
        (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
            .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
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
        return b.setContentTitle("Device agent")
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
    } catch (e: Exception) { Log.w(TAG, "read: ${e.message}"); null }

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
    } catch (e: Exception) { Log.w(TAG, "write: ${e.message}"); false }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL = "agent_mgmt"
        private const val NOTIF_ID = 43
        private const val POLL_MS = 45_000L

        fun start(context: Context) {
            val i = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
