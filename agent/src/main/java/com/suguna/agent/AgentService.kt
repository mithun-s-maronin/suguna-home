package com.suguna.agent

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Companion agent service. Runs as a device ADMIN (not owner), so it installs alongside the
 * existing MDM without a reset and without re-provisioning Device Owner. Every POLL_MS it signs
 * in anonymously (FbAuth) and writes this tablet's status to /devices/<uid> (name, enrollment,
 * battery, GPS, current app, lock state), then runs any command the admin panel left:
 *   "lock"          -> full-screen lockdown overlay, only the panel's "unlock" releases it
 *   "unlock"        -> remove the lock
 *   "factory_reset" -> wipe the tablet (device-admin wipe; best-effort)
 *
 * Auth is Firebase Anonymous Auth over REST — no secret, only the public API key ships.
 * (The MDM already handles the kiosk and screen-lock reset, which need Device Owner.)
 */
class AgentService : Service() {

    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (Prefs(this).permLocked) showOverlay()
        if (!running) {
            running = true
            Thread { loop() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    private fun base() = Config.FIREBASE_DB_URL.trimEnd('/')
    private fun dpm() = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun loop() {
        while (running) {
            if (Prefs(this).permLocked && overlay == null) showOverlay()
            try {
                val token = FbAuth.idToken(this)
                if (token != null) { checkIn(token); pollCommand(token) }
            } catch (e: Exception) { Log.w(TAG, "cycle: ${e.message}") }
            try { Thread.sleep(POLL_MS) } catch (e: InterruptedException) { break }
        }
    }

    private fun checkIn(token: String) {
        val uid = FbAuth.uid(this) ?: return
        val prefs = Prefs(this)
        val body = JSONObject()
            .put("name", prefs.studentName)
            .put("enrollment", prefs.enrollmentNumber)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("appVersion", "agent")
            .put("androidId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
            .put("battery", batteryPct())
            .put("currentApp", foregroundApp())
            .put("lockState", if (prefs.permLocked) "locked" else "none")
            .put("lastSeen", System.currentTimeMillis())
        val loc = lastLocation()
        if (loc != null) {
            body.put("lat", loc.latitude)
            body.put("lng", loc.longitude)
            body.put("locAccuracy", loc.accuracy.toInt())
            body.put("locTime", loc.time)
        }
        httpWrite("PATCH", "${base()}/devices/$uid.json?auth=$token", body.toString())
    }

    private fun pollCommand(token: String) {
        val uid = FbAuth.uid(this) ?: return
        val txt = httpRead("${base()}/devices/$uid/command.json?auth=$token") ?: return
        if (txt.isBlank() || txt == "null") return
        val action = try { JSONObject(txt).optString("action") } catch (e: Exception) { "" }
        if (action.isBlank()) return
        Log.i(TAG, "command: $action")
        // Consume the command so it only runs once.
        httpWrite("DELETE", "${base()}/devices/$uid/command.json?auth=$token", null)
        when (action) {
            "lock" -> { Prefs(this).permLocked = true; showOverlay() }
            "unlock" -> { Prefs(this).permLocked = false; hideOverlay() }
            "factory_reset" -> factoryReset()
            // reset_screen_lock is Device-Owner only — handled by the MDM, not the agent.
        }
    }

    private fun factoryReset() {
        val admin = AgentAdminReceiver.component(this)
        if (!dpm().isAdminActive(admin)) { Log.w(TAG, "not an active admin; wipe skipped"); return }
        try {
            dpm().wipeData(0)
        } catch (e: Exception) {
            Log.w(TAG, "wipeData failed: ${e.message}")
        }
    }

    // ---- Lock overlay ------------------------------------------------------------------------

    private fun showOverlay() {
        main.post {
            if (overlay != null) return@post
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "overlay permission not granted; lock cannot cover the screen")
                return@post
            }
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#0D1B2A"))
                isClickable = true
                isFocusable = true
                addView(TextView(this@AgentService).apply {
                    text = "🔒"; textSize = 64f; gravity = Gravity.CENTER
                })
                addView(TextView(this@AgentService).apply {
                    text = "Device locked"; setTextColor(Color.WHITE); textSize = 26f
                    gravity = Gravity.CENTER; setPadding(0, 32, 0, 0)
                })
                addView(TextView(this@AgentService).apply {
                    text = "This tablet has been locked by the administrator.\nIt will be unlocked remotely."
                    setTextColor(Color.parseColor("#9FB3C8")); textSize = 15f
                    gravity = Gravity.CENTER; setPadding(48, 16, 48, 0)
                })
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.OPAQUE,
            )
            try { wm.addView(view, lp); overlay = view }
            catch (e: Exception) { Log.w(TAG, "overlay add: ${e.message}") }
        }
    }

    private fun hideOverlay() {
        main.post {
            val v = overlay ?: return@post
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (e: Exception) { Log.w(TAG, "overlay remove: ${e.message}") }
            overlay = null
        }
    }

    // ---- Telemetry helpers -------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        val fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
            best
        } catch (e: Exception) { Log.w(TAG, "loc: ${e.message}"); null }
    }

    private fun usageAccessGranted(): Boolean = try {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    private fun foregroundApp(): String {
        if (!usageAccessGranted()) return ""
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000, now)
            val e = UsageEvents.Event()
            var pkg = ""
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = e.packageName
            }
            if (pkg.isBlank()) "" else appLabel(pkg)
        } catch (e: Exception) { Log.w(TAG, "fg: ${e.message}"); "" }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        "${pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))}|$pkg"
    } catch (e: Exception) { pkg }

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

    // ---- HTTP --------------------------------------------------------------------------------

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
        private const val POLL_MS = 30_000L

        fun start(context: Context) {
            val i = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
