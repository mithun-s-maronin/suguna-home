package com.suguna.home

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen — a recreation of the original Sure MDM LauncherActivity.
 * Layout, colors, fonts, text formats and controls are the original's (activity_launcher.xml).
 */
class LauncherActivity : Activity() {

    private class LauncherApp(val packageName: String, val label: String, val icon: Drawable)

    private lateinit var prefs: Prefs
    private lateinit var installer: AppInstaller

    private lateinit var tvTime: TextView
    private lateinit var tvClockLarge: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvStudentName: TextView
    private lateinit var tvStudentId: TextView
    private lateinit var tvFooter: TextView
    private lateinit var rvApps: GridView
    private lateinit var btnConnectionStatus: ImageButton
    private lateinit var btnInfo: ImageButton
    private lateinit var btnClearRecents: ImageButton
    private lateinit var vBrightnessTouch: View
    private lateinit var llBrightnessIndicator: View
    private lateinit var tvBrightnessPercent: TextView

    private var apps: List<LauncherApp> = emptyList()
    private val appAdapter = AppAdapter()

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wallpaperDownloadStarted = false

    private var footerTapCount = 0
    private var lastFooterTapAt = 0L
    private var footerArmed = false
    private var holdRunnable: Runnable? = null

    /** Change the volume on key press but don't show the system volume slider on the home screen. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val dir = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        AudioManager.ADJUST_RAISE
                    } else {
                        AudioManager.ADJUST_LOWER
                    }
                    // Flag 0 = adjust silently, no on-screen volume bar.
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_MUTE -> return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.setupComplete) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_launcher)
        tvTime = findViewById(R.id.tvTime)
        tvClockLarge = findViewById(R.id.tvClockLarge)
        tvDate = findViewById(R.id.tvDate)
        tvBattery = findViewById(R.id.tvBattery)
        tvStudentName = findViewById(R.id.tvStudentName)
        tvStudentId = findViewById(R.id.tvStudentId)
        rvApps = findViewById(R.id.rvApps)
        tvFooter = findViewById(R.id.tvFooter)
        btnConnectionStatus = findViewById(R.id.btnConnectionStatus)
        btnInfo = findViewById(R.id.btnInfo)
        btnClearRecents = findViewById(R.id.btnClearRecents)
        vBrightnessTouch = findViewById(R.id.vBrightnessTouch)
        llBrightnessIndicator = findViewById(R.id.llBrightnessIndicator)
        tvBrightnessPercent = findViewById(R.id.tvBrightnessPercent)

        installer = AppInstaller(this)

        rvApps.adapter = appAdapter
        rvApps.setOnItemClickListener { _, _, position, _ -> launchApp(apps[position]) }

        updateFooter()
        setupAdminGesture()

        // Opening the MDM restores it as the home screen (in case "Exit MDM" handed HOME away).
        Kiosk.enableMdmHome(this)

        // Start the remote-admin agent: reports status to Firebase and runs lock / factory-reset.
        RemoteAdminService.start(this)

        // After the update the app is already "set up", so its new permissions were never
        // requested. Show the one-time gate if they're still missing — but not if the user
        // already skipped it this session (prevents a loop when they press Home).
        if (!Perms.allGranted(this) && !PermissionGateActivity.skipped) {
            try { startActivity(Intent(this, PermissionGateActivity::class.java)) }
            catch (e: Exception) { Log.w("Launcher", "perm gate: ${e.message}") }
        }

        // Clean up the sideloaded installer APK if it's still in Download (best-effort).
        try { SelfClean.deleteInstallerApks(this) } catch (e: Exception) { /* ignore */ }

        setupBrightnessControl()
        setupWifiButton()
        setupClearRecentsButton()
        setupInfoButton()
        setupConnectionStatusButton()
        loadApps()
        updateClock()
    }

    /**
     * Admin entry: 7 quick taps on the top-left time arm it silently (no pop-up); holding the time
     * for 3 seconds then asks for the 6-digit PIN.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupAdminGesture() {
        tvTime.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (footerArmed) {
                        val r = Runnable {
                            footerArmed = false
                            footerTapCount = 0
                            promptAdminPin()
                        }
                        holdRunnable = r
                        handler.postDelayed(r, 3000L)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdRunnable?.let { handler.removeCallbacks(it) }
                    holdRunnable = null
                    val heldMs = event.eventTime - event.downTime
                    if (heldMs < 400 && !footerArmed) {
                        val now = System.currentTimeMillis()
                        footerTapCount = if (now - lastFooterTapAt < 2500L) footerTapCount + 1 else 1
                        lastFooterTapAt = now
                        if (footerTapCount >= 7) {
                            // Arm silently — no pop-up. Hold for 3 seconds to open the PIN.
                            footerArmed = true
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Admin PIN entry. Max 3 attempts: a 30-second wait is enforced before the 3rd attempt, and a
     * 3rd wrong attempt factory-resets the device (Device Owner only).
     */
    private fun promptAdminPin() {
        val remainingLockout = prefs.pinLockoutUntil - System.currentTimeMillis()
        if (remainingLockout > 0) {
            Toast.makeText(
                this,
                "Wait ${(remainingLockout / 1000) + 1} seconds before the last attempt",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "6-digit PIN"
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        AlertDialog.Builder(this)
            .setTitle("Enter admin PIN")
            .setView(input)
            .setPositiveButton("Open") { _, _ -> checkAdminPin(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAdminPin(entered: String) {
        if (entered == prefs.adminPin) {
            prefs.pinFailCount = 0
            prefs.pinLockoutUntil = 0L
            startActivity(Intent(this, AdminActivity::class.java))
            return
        }

        val fails = prefs.pinFailCount + 1
        prefs.pinFailCount = fails
        when {
            fails >= 3 -> {
                // Final wrong attempt → wipe the device. On success the device wipes immediately;
                // if it can't, show the real reason (and keep the counter maxed, not reset to 0).
                val error = Kiosk.factoryReset(this)
                if (error != null) {
                    Toast.makeText(this, "Reset failed — $error", Toast.LENGTH_LONG).show()
                }
            }
            fails == 2 -> {
                prefs.pinLockoutUntil = System.currentTimeMillis() + 30_000L
                Toast.makeText(
                    this,
                    "Wrong PIN. Wait 30 seconds — 1 attempt left.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            else -> {
                Toast.makeText(this, "Wrong PIN. 2 attempts left.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::rvApps.isInitialized) return
        if (!prefs.setupComplete) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        handler.post(clockRunnable)
        loadApps()
        updateFooter()
        applyWallpaper()
        registerNetworkCallback()
        updateConnectionStatus(isWifiConnected())
        // Device Owner only: hide Home + Recents, block the shade, keep Back and the bar. Silent
        // (no screen pinning). A normally-installed app behaves like an ordinary launcher.
        Kiosk.applyKiosk(this)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
        unregisterNetworkCallback()
    }

    override fun onDestroy() {
        if (::installer.isInitialized) installer.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Home screen: Back does nothing (same as the original).
    }

    // ── Wallpaper ──────────────────────────────────────────────────────

    private fun applyWallpaper() {
        val root = findViewById<View>(R.id.rootLauncher)
        val cacheFile = File(cacheDir, WALLPAPER_CACHE_FILE)
        if (!cacheFile.exists()) {
            root.setBackgroundColor(Color.parseColor("#1A1A2E"))
            downloadWallpaperIfConfigured()
            return
        }
        try {
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cacheFile.absolutePath, bounds)
            val sample = maxOf(bounds.outWidth / (screenW * 2), maxOf(bounds.outHeight / (screenH * 2), 1))
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
            if (bitmap == null) {
                Log.w(TAG, "Wallpaper decode returned null (OOM or corrupt)")
                return
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, screenW, screenH, true)
            if (scaled != bitmap) bitmap.recycle()
            root.background = BitmapDrawable(resources, scaled)
            Log.i(TAG, "Wallpaper applied ${screenW}x$screenH (sample=$sample)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply wallpaper: ${e.message}")
        }
    }

    /** The original got the wallpaper from the MDM server; here it comes from AppConfig.WALLPAPER_URL. */
    private fun downloadWallpaperIfConfigured() {
        val url = AppConfig.WALLPAPER_URL
        if (url.isBlank() || wallpaperDownloadStarted) return
        wallpaperDownloadStarted = true
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    File(cacheDir, WALLPAPER_CACHE_FILE).outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    runOnUiThread { applyWallpaper() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wallpaper download failed: ${e.message}")
            } finally {
                wallpaperDownloadStarted = false
            }
        }.start()
    }

    // ── Top bar buttons ────────────────────────────────────────────────

    private fun setupWifiButton() {
        findViewById<ImageButton>(R.id.btnWifi).setOnClickListener {
            startActivity(Intent(this, WifiConnectActivity::class.java))
        }
    }

    private fun setupInfoButton() {
        btnInfo.setOnClickListener { showInfoDialog() }
    }

    private fun setupConnectionStatusButton() {
        updateConnectionStatus(isWifiConnected())
        btnConnectionStatus.setOnClickListener { updateConnectionStatus(isWifiConnected()) }
    }

    private fun updateConnectionStatus(connected: Boolean) {
        // Green when Wi-Fi is connected, red when it is not.
        val color = if (connected) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        btnConnectionStatus.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        btnConnectionStatus.contentDescription = if (connected) "Wi-Fi connected" else "Wi-Fi off"
    }

    /** True only when connected to a Wi-Fi network (ignores mobile data / LTE). */
    @Suppress("DEPRECATION")
    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true
        }
        return false
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = post()
            override fun onLost(network: Network) = post()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = post()
            private fun post() {
                runOnUiThread { if (::btnConnectionStatus.isInitialized) updateConnectionStatus(isWifiConnected()) }
            }
        }
        try {
            // Watch Wi-Fi specifically, so the dot reflects Wi-Fi rather than any internet.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Network callback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // ignore
        }
        networkCallback = null
    }

    private fun setupClearRecentsButton() {
        btnClearRecents.setOnClickListener { clearRecentApps() }
    }

    /**
     * The original opened Recents and tapped "Clear all" through an accessibility service.
     * Accessibility apps are blocked by Play Protect when sideloaded (India), so this closes
     * the background apps instead (KILL_BACKGROUND_PROCESSES, a normal permission).
     */
    @Suppress("DEPRECATION")
    private fun clearRecentApps() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = packageManager.queryIntentActivities(launcherQuery, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .toSet()
        for (pkg in packages) {
            try {
                am.killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "Could not close $pkg: ${e.message}")
            }
        }
        Log.i(TAG, "Closed background apps: ${packages.size}")
    }

    // ── Device Info dialog ─────────────────────────────────────────────

    private fun showInfoDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_device_info, null)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        view.findViewById<TextView>(R.id.tvDeviceId).text = deviceId

        val btnVerify = view.findViewById<Button>(R.id.btnVerifyDevice)
        val tvVerified = view.findViewById<TextView>(R.id.tvVerifiedBadge)
        fun showVerified(verified: Boolean) {
            btnVerify.visibility = if (verified) View.GONE else View.VISIBLE
            tvVerified.visibility = if (verified) View.VISIBLE else View.GONE
        }
        showVerified(prefs.deviceVerified)
        btnVerify.setOnClickListener {
            btnVerify.isEnabled = false
            btnVerify.text = "Verifying…"
            verifyDevice(deviceId) { success, message ->
                if (success) {
                    prefs.deviceVerified = true
                    showVerified(true)
                } else {
                    btnVerify.isEnabled = true
                    btnVerify.text = "Verify"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

        // Storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val storageTotal = stat.blockCountLong * stat.blockSizeLong
        val storageFree = stat.availableBlocksLong * stat.blockSizeLong
        val storageUsed = storageTotal - storageFree
        val storagePct = if (storageTotal > 0) ((100 * storageUsed) / storageTotal).toInt() else 0
        view.findViewById<ProgressBar>(R.id.pbStorage).progress = storagePct
        view.findViewById<TextView>(R.id.tvStoragePercent).text = "$storagePct% used"
        view.findViewById<TextView>(R.id.tvStorageUsed).text = "Used: " + fmtBytes(storageUsed)
        view.findViewById<TextView>(R.id.tvStorageFree).text = "Free: " + fmtBytes(storageFree)

        // RAM
        val memInfo = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memInfo)
        val ramUsed = memInfo.totalMem - memInfo.availMem
        val ramPct = if (memInfo.totalMem > 0) ((100 * ramUsed) / memInfo.totalMem).toInt() else 0
        view.findViewById<ProgressBar>(R.id.pbRam).progress = ramPct
        view.findViewById<TextView>(R.id.tvRamPercent).text = "$ramPct% used"
        view.findViewById<TextView>(R.id.tvRamUsed).text = "Used: " + fmtBytes(ramUsed)
        view.findViewById<TextView>(R.id.tvRamFree).text = "Free: " + fmtBytes(memInfo.availMem)

        // Allowed apps
        val allowedApps = AppConfig.MANAGED_APPS.map { it.packageName }
        view.findViewById<TextView>(R.id.tvAppsCount).text = "${allowedApps.size} apps"
        val llApps = view.findViewById<LinearLayout>(R.id.llAppsList)
        for (pkg in allowedApps) {
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            row.addView(TextView(this).apply {
                text = "•"
                setTextColor(0xFF2962FF.toInt())
                textSize = 14f
                setPadding(0, 0, dp(10), 0)
            })
            row.addView(TextView(this).apply {
                text = label
                setTextColor(0xFFB0BEC5.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = pkg.substringAfterLast(".")
                setTextColor(0xFF546E7A.toInt())
                textSize = 11f
            })
            llApps.addView(row)
            if (pkg == allowedApps.last()) {
                llApps.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also {
                        it.setMargins(dp(14), 0, dp(14), 0)
                    }
                    setBackgroundColor(0xFF2A2A3E.toInt())
                })
            }
        }

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<TextView>(R.id.tvDialogClose).setOnClickListener { dialog.dismiss() }

        view.findViewById<Button>(R.id.btnUpdateApps).setOnClickListener {
            for (app in AppConfig.MANAGED_APPS) installer.installIfNewer(app.downloadUrl, app.packageName)
            Toast.makeText(this, "Checking ${AppConfig.MANAGED_APPS.size} app(s) for updates…", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnUpdateMdm).setOnClickListener {
            if (AppConfig.SELF_UPDATE_URL.isNotBlank()) {
                installer.installIfNewer(AppConfig.SELF_UPDATE_URL, packageName)
            }
            Toast.makeText(this, "Checking for MDM update…", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnClearDownloads).setOnClickListener {
            val count = clearLauncherDownloads()
            Toast.makeText(this, "Cleared $count file(s)", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnClearData).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear App Data?")
                .setMessage("This will reset all data (settings, logins) for ${allowedApps.size} allowed app(s). Cannot be undone.")
                .setPositiveButton("Clear") { _, _ -> clearAllowedAppData(allowedApps) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            (resources.displayMetrics.heightPixels * 0.8).toInt(),
        )
    }

    /**
     * Verify is Wi-Fi-based until a backend exists: if Wi-Fi is connected the device verifies, and
     * if it isn't, it shows the original Sure MDM default ("Network error — check your connection").
     * When your own server is ready this can call its /api/mob/device/verify instead.
     */
    private fun verifyDevice(deviceId: String, onResult: (Boolean, String) -> Unit) {
        if (isWifiConnected()) {
            onResult(true, "Device verified")
        } else {
            onResult(false, "Network error — check your connection")
        }
    }

    private fun clearLauncherDownloads(): Int {
        var count = 0
        cacheDir.listFiles()?.forEach { if (it.delete()) count++ }
        count += installer.clearDownloadedApks()
        return count
    }

    /** The original cleared other apps' data as Device Owner; a normal app can only open App info. */
    private fun clearAllowedAppData(allowedApps: List<String>) {
        val installed = allowedApps.filter { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0); true
            } catch (e: Exception) {
                false
            }
        }
        if (installed.isEmpty()) {
            Toast.makeText(this, "No allowed apps installed", Toast.LENGTH_SHORT).show()
            return
        }

        // Device Owner can clear other apps' data directly (API 28+); no manual Settings step.
        if (Kiosk.isDeviceOwner(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = MdmDeviceAdminReceiver.component(this)
            var requested = 0
            for (pkg in installed) {
                try {
                    dpm.clearApplicationUserData(admin, pkg, mainExecutor) { p, ok ->
                        Log.i(TAG, "Cleared data for $p: $ok")
                    }
                    requested++
                } catch (e: Exception) {
                    Log.w(TAG, "clearApplicationUserData $pkg failed: ${e.message}")
                }
            }
            Toast.makeText(this, "App data cleared", Toast.LENGTH_SHORT).show()
            return
        }

        // Fallback (not Device Owner): open the app's info page.
        Toast.makeText(this, "Tap \"Storage\" → \"Clear data\"", Toast.LENGTH_LONG).show()
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${installed.first()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun fmtBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format("%.1f GB", bytes / 1.073741824E9)
        bytes < 1_048_576L -> String.format("%.0f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / 1048576.0)
    }

    // ── Apps grid ──────────────────────────────────────────────────────

    private fun loadApps() {
        // Pinned apps chosen in the admin screen; falls back to the three managed apps.
        val pinned = prefs.pinnedApps.ifEmpty { AppConfig.MANAGED_APPS.map { it.packageName }.toSet() }
        apps = pinned
            .mapNotNull { resolveApp(it) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        appAdapter.notifyDataSetChanged()
        Log.i(TAG, "Loaded ${apps.size} launcher apps")

        val studentName = prefs.studentName
        if (studentName.isEmpty()) {
            tvStudentName.visibility = View.GONE
        } else {
            tvStudentName.text = studentName
            tvStudentName.visibility = View.VISIBLE
        }
        val enrollmentNumber = prefs.enrollmentNumber
        if (enrollmentNumber.isEmpty()) {
            tvStudentId.visibility = View.GONE
        } else {
            tvStudentId.text = "Enrollment: $enrollmentNumber"
            tvStudentId.visibility = View.VISIBLE
        }
    }

    private fun resolveApp(packageName: String): LauncherApp? {
        val pm = packageManager
        return try {
            if (pm.getLaunchIntentForPackage(packageName) == null) return null
            val appInfo = pm.getApplicationInfo(packageName, 0)
            LauncherApp(packageName, pm.getApplicationLabel(appInfo).toString(), pm.getApplicationIcon(appInfo))
        } catch (e: Exception) {
            Log.w(TAG, "App not installed: $packageName")
            null
        }
    }

    private fun launchApp(app: LauncherApp) {
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Log.w(TAG, "No launch intent for: ${app.packageName}")
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_launcher_app, parent, false)
            val app = apps[position]
            view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.tvAppName).text = app.label
            return view
        }
    }

    // ── Clock + battery ────────────────────────────────────────────────

    /** Footer version string — admin-editable, defaults to the app's own versionName. */
    private fun updateFooter() {
        val version = prefs.footerVersion.ifBlank {
            try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
        }
        tvFooter.text = "Managed Device · v$version"
    }

    private fun updateClock() {
        val now = Date()
        tvTime.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        tvClockLarge.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
        tvDate.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        tvBattery.text = "🔋 $level%"
    }

    // ── Brightness swipe (right edge) ──────────────────────────────────

    private fun setupBrightnessControl() {
        var startY = 0f
        var startBrightness = 0f
        vBrightnessTouch.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    startBrightness = window.attributes.screenBrightness
                    if (startBrightness < 0f) {
                        startBrightness = try {
                            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                        } catch (e: Settings.SettingNotFoundException) {
                            0.5f
                        }
                    }
                    llBrightnessIndicator.visibility = View.VISIBLE
                    updateBrightnessUI(startBrightness)
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = startY - event.y
                    val screenHeight = resources.displayMetrics.heightPixels
                    val newBrightness = (startBrightness + deltaY / (screenHeight / 2f)).coerceIn(0.05f, 1f)
                    val lp = window.attributes
                    lp.screenBrightness = newBrightness
                    window.attributes = lp
                    updateBrightnessUI(newBrightness)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    llBrightnessIndicator.visibility = View.GONE
                    val finalBrightness = window.attributes.screenBrightness
                    val brightnessInt = (255 * finalBrightness).toInt().coerceIn(10, 255)
                    try {
                        if (Settings.System.canWrite(this)) {
                            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessInt)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save system brightness", e)
                    }
                }
            }
            true
        }
    }

    private fun updateBrightnessUI(brightness: Float) {
        tvBrightnessPercent.text = "${(100 * brightness).toInt()}%"
    }

    companion object {
        private const val TAG = "LauncherActivity"
        const val WALLPAPER_CACHE_FILE = "mdm_wallpaper_cache.jpg"
    }
}
