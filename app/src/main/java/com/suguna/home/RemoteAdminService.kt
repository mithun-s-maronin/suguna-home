package com.suguna.home

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
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
 * Remote-admin agent (merged into the fork for v35). Runs as a foreground service and every
 * POLL_MS:
 *   1. writes this device's status to /devices/<id>  — name, enrollment, model, battery,
 *      GPS location, current foreground app, lock state, lastSeen
 *   2. reads /devices/<id>/command and, if present, executes it and deletes it.
 *
 * Commands (sent by the Suguna Admin app):
 *   "lock"           -> lock the screen now (student can unlock normally)
 *   "lock_permanent" -> full-screen overlay + lock, re-asserted until "unlock"
 *   "unlock"         -> release the permanent lock
 *   "factory_reset"  -> wipe the device (reuses Kiosk.factoryReset)
 *
 * Firebase Realtime Database over REST — no Firebase SDK. The DB URL + secret come from
 * BuildConfig (injected at build time), so the secret never lives in the source.
 */
class RemoteAdminService : Service() {

    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var geminiReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Side-button long-press → Gemini "Ask about screen". Lives here because this service is
        // always running (foreground, restarted on boot), so the listener is always active.
        geminiReceiver = GeminiAssist.register(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
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

    override fun onDestroy() {
        running = false
        GeminiAssist.unregister(this, geminiReceiver)
        geminiReceiver = null
        super.onDestroy()
    }

    private fun base() = BuildConfig.FIREBASE_DB_URL.trimEnd('/')
    private fun secret() = BuildConfig.FIREBASE_DB_SECRET

    private fun loop() {
        while (running) {
            if (Prefs(this).permLocked && overlay == null) showOverlay()
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
        // Clear it first so it runs once even if the action restarts/wipes the device.
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
                addView(TextView(this@RemoteAdminService).apply {
                    text = "🔒"
                    textSize = 64f
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@RemoteAdminService).apply {
                    text = "Device locked"
                    setTextColor(Color.WHITE)
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setPadding(0, 32, 0, 0)
                })
                addView(TextView(this@RemoteAdminService).apply {
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
