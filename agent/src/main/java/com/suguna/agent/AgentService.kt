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
 * Companion agent service. Runs as a device ADMIN (not owner), so it can be added alongside the
 * existing fork without a reset. Every POLL_MS it checks in to Firebase (name, enrollment, battery,
 * GPS location, current foreground app, lock state) and runs commands from the admin panel:
 *   "lock"           -> dpm.lockNow()                       (student can unlock normally)
 *   "lock_permanent" -> full-screen overlay + lockNow(),    (only "unlock" from the panel releases it)
 *                       re-asserted on every cycle / reboot
 *   "unlock"         -> clears the permanent lock
 */
class AgentService : Service() {

    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // Re-assert a permanent lock that was in force before a restart / reboot.
        if (Prefs(this).permLocked) showOverlay()
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
            if (Prefs(this).permLocked && overlay == null) showOverlay()
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
            .put("currentApp", foregroundApp())
            .put("lockState", if (prefs.permLocked) "permanent" else "none")
            .put("lastSeen", System.currentTimeMillis())
        val loc = lastLocation()
        if (loc != null) {
            body.put("lat", loc.latitude)
            body.put("lng", loc.longitude)
            body.put("locAccuracy", loc.accuracy.toInt())
            body.put("locTime", loc.time)
        }
        httpWrite("PATCH", "${base()}/devices/${deviceId()}.json?auth=${secret()}", body.toString())
    }

    private fun pollCommand() {
        val txt = httpRead("${base()}/devices/${deviceId()}/command.json?auth=${secret()}") ?: return
        if (txt.isBlank() || txt == "null") return
        val action = try { JSONObject(txt).optString("action") } catch (e: Exception) { "" }
        if (action.isBlank()) return
        Log.i(TAG, "command: $action")
        // Consume the command so it only runs once.
        httpWrite("DELETE", "${base()}/devices/${deviceId()}/command.json?auth=${secret()}", null)

        when (action) {
            "lock" -> lockNow()
            "lock_permanent" -> {
                Prefs(this).permLocked = true
                showOverlay()
                lockNow()
            }
            "unlock" -> {
                Prefs(this).permLocked = false
                hideOverlay()
            }
        }
    }

    private fun lockNow() {
        val admin = AgentAdminReceiver.component(this)
        if (dpm().isAdminActive(admin)) {
            try { dpm().lockNow() } catch (e: Exception) { Log.w(TAG, "lock: ${e.message}") }
        } else {
            Log.w(TAG, "not an active admin; keyguard lock skipped")
        }
    }

    // ---- Permanent-lock overlay --------------------------------------------------------------

    private fun showOverlay() {
        main.post {
            if (overlay != null) return@post
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "overlay permission not granted; permanent lock cannot cover the screen")
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
                    text = "🔒"
                    textSize = 64f
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@AgentService).apply {
                    text = "Device locked"
                    setTextColor(Color.WHITE)
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setPadding(0, 32, 0, 0)
                })
                addView(TextView(this@AgentService).apply {
                    text = "This device has been locked by the administrator.\nIt will be unlocked remotely."
                    setTextColor(Color.parseColor("#9FB3C8"))
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(48, 16, 48, 0)
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
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        mode == AppOpsManager.MODE_ALLOWED
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
        val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        "$label|$pkg"
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
        private const val POLL_MS = 45_000L

        fun start(context: Context) {
            val i = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
