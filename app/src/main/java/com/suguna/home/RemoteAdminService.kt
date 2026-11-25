package com.suguna.home

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
 * Remote-admin link to the Suguna Admin panel (built into the MDM for v36). Runs as a foreground
 * service. Every POLL_MS it:
 *   1. signs in anonymously if needed (FbAuth) and writes this tablet's status to /devices/<uid>
 *      — name, enrollment, model, battery, GPS, current app, lock state, lastSeen
 *   2. reads /devices/<uid>/command and, if one is waiting, runs it and clears it.
 *
 * Commands from the panel:
 *   "lock"              -> full-screen lockdown, only the panel's "unlock" releases it
 *   "unlock"            -> release the lock
 *   "factory_reset"     -> wipe the tablet (Device Owner)
 *   "reset_screen_lock" -> clear a forgotten screen PIN (Device Owner, via a provisioned token)
 *
 * A lightweight watchdog also snaps the tablet back to the MDM if a student slips out to the
 * real home screen.
 *
 * Auth is Firebase Anonymous Auth over REST (no SDK, no secret) — see FbAuth. Only the public
 * API key ships; the Realtime Database rules keep each tablet to its own record.
 */
class RemoteAdminService : Service() {

    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // Provision the reset-password token early so "reset screen lock" works later.
        try { Kiosk.provisionResetToken(this) } catch (e: Exception) { Log.w(TAG, "token: ${e.message}") }
        if (!running) {
            running = true
            Thread { loop() }.start()          // periodic status check-in (telemetry)
            Thread { streamLoop() }.start()    // live command stream (instant lock/unlock/…)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun base() = AppConfig.FIREBASE_DB_URL.trimEnd('/')

    private fun loop() {
        while (running) {
            try {
                val token = FbAuth.idToken(this)
                if (token != null) checkIn(token)
            } catch (e: Exception) {
                Log.w(TAG, "cycle failed: ${e.message}")
            }
            try { Thread.sleep(POLL_MS) } catch (e: InterruptedException) { break }
        }
    }

    // ── Check-in + commands ─────────────────────────────────────────────────

    private fun checkIn(token: String) {
        val uid = FbAuth.uid(this) ?: return
        val prefs = Prefs(this)
        val body = JSONObject()
            .put("name", prefs.studentName)
            .put("enrollment", prefs.enrollmentNumber)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("appVersion", appVersion())
            .put("androidId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
            .put("battery", batteryPct())
            .put("currentApp", foregroundApp())
            .put("lockState", if (prefs.permLocked) "locked" else "none")
            .put("lastInstall", prefs.lastInstall)
            .put("lastSeen", System.currentTimeMillis())
        httpWrite("PATCH", "${base()}/devices/$uid.json?auth=$token", body.toString())
    }

    /**
     * Live command channel. Instead of polling, hold one long-lived event-stream connection to
     * /devices/<uid>/command.json. Firebase pushes a "put" the instant the panel writes a command,
     * so lock/unlock/reset happen with ~1s latency. On connect it also replays the current value,
     * so a command queued while the tablet was offline fires the moment it reconnects.
     */
    private fun streamLoop() {
        while (running) {
            val uid = FbAuth.uid(this)
            val token = try { FbAuth.idToken(this) } catch (e: Exception) { null }
            if (uid == null || token == null) { trySleep(2000); continue }
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("${base()}/devices/$uid/command.json?auth=$token").openConnection()
                    as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "text/event-stream")
                    connectTimeout = 15000
                    readTimeout = 70000   // longer than Firebase's ~30s keep-alive; else reconnect
                }
                if (conn.responseCode !in 200..299) { trySleep(3000); continue }
                val reader = conn.inputStream.bufferedReader()
                var event = ""
                while (running) {
                    val line = reader.readLine() ?: break   // stream closed by server
                    when {
                        line.startsWith("event:") -> {
                            event = line.substring(6).trim()
                            if (event == "auth_revoked" || event == "cancel") break  // reconnect w/ fresh token
                        }
                        line.startsWith("data:") ->
                            if (event == "put" || event == "patch") handleStreamData(line.substring(5).trim(), token)
                    }
                }
            } catch (e: Throwable) {
                // Never let the command stream kill the process — that would also stop check-in.
                Log.w(TAG, "stream: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
            if (running) trySleep(2000)   // brief backoff before reconnecting
        }
    }

    /** Parse an event-stream payload {"path":"/","data":{...}} and run any command it carries. */
    private fun handleStreamData(data: String, token: String) {
        val cmd = try {
            JSONObject(data).opt("data") as? JSONObject   // null = command cleared
        } catch (e: Exception) { null }
        if (cmd != null && cmd.optString("action").isNotBlank()) runCommand(cmd, token)
    }

    private fun runCommand(cmd: JSONObject, token: String) {
        val uid = FbAuth.uid(this) ?: return
        val action = cmd.optString("action")
        Log.i(TAG, "command: $action")
        // Clear it first so it runs once even if the action restarts/wipes the tablet.
        httpWrite("DELETE", "${base()}/devices/$uid/command.json?auth=$token", null)
        when (action) {
            "lock" -> {
                // Lock down the tablet: pin to the MDM alone (every student app blocked), snap to
                // the MDM home (kicks the student out of any app they're in), and seal the admin/exit
                // panel. No banner. The admin's own tablet is exempt from "Lock all", so it's never
                // sent this and keeps working.
                Prefs(this).permLocked = true
                try { Kiosk.lockToMdm(this) } catch (e: Exception) { Log.w(TAG, "lockToMdm: ${e.message}") }
                notifyLockState()
            }
            "unlock" -> {
                // Restore the full app allowlist right away so the student apps work again without
                // waiting for the home screen to refresh.
                Prefs(this).permLocked = false
                try { Kiosk.restoreApps(this) } catch (e: Exception) { Log.w(TAG, "restoreApps: ${e.message}") }
                try { Kiosk.relaunchHome(this) } catch (e: Exception) { Log.w(TAG, "relaunchHome: ${e.message}") }
                notifyLockState()
            }
            "factory_reset" -> Kiosk.factoryReset(this)
            "reset_screen_lock" -> {
                val ok = Kiosk.resetScreenLock(this)
                Log.i(TAG, "reset_screen_lock -> $ok")
            }
            "install_apk" -> {
                val url = cmd.optString("url")
                val label = cmd.optString("label", "app")
                if (url.isNotBlank()) {
                    // Download + install can take a while; don't block the command stream.
                    Thread { ApkInstaller.install(this, url, label) }.start()
                }
            }
        }
        // Push the new lock state back right away so the panel reflects it on its next refresh,
        // instead of waiting up to a full check-in cycle.
        if (action == "lock" || action == "unlock") {
            try { checkIn(token) } catch (e: Exception) { Log.w(TAG, "post-cmd checkIn: ${e.message}") }
        }
    }

    /** Tell a visible launcher to show/hide its locked banner immediately. */
    private fun notifyLockState() {
        try {
            sendBroadcast(Intent(LauncherActivity.ACTION_LOCK_STATE_CHANGED).setPackage(packageName))
        } catch (e: Exception) { Log.w(TAG, "notifyLockState: ${e.message}") }
    }

    private fun trySleep(ms: Long) { try { Thread.sleep(ms) } catch (e: InterruptedException) {} }

    // ── Telemetry helpers ────────────────────────────────────────────────────

    private fun usageAccessGranted(): Boolean = try {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    private fun foregroundPackage(): String {
        if (!usageAccessGranted()) return ""
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10_000, now)
            val e = UsageEvents.Event()
            var pkg = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = e.packageName
            }
            pkg
        } catch (e: Exception) { "" }
    }

    private fun foregroundApp(): String {
        val pkg = foregroundPackage()
        return if (pkg.isBlank()) "" else appLabel(pkg)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        "${pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))}|$pkg"
    } catch (e: Exception) { pkg }

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

    // ── HTTP ─────────────────────────────────────────────────────────────────

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
        private const val POLL_MS = 30_000L
        private const val WATCH_MS = 2_000L

        /** Start the link service (from LauncherActivity and on boot). */
        fun start(context: Context) {
            val i = Intent(context, RemoteAdminService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
