package com.suguna.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Admin screen (reached by long-pressing the footer on the home screen):
 * pin apps to the home screen, set the wallpaper, and edit the footer version.
 */
class AdminActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var etVersion: EditText
    private lateinit var tvWallpaperState: TextView
    private lateinit var appCheckList: LinearLayout

    /** packageName → its checkbox */
    private val checkBoxes = linkedMapOf<String, CheckBox>()

    /** A locked device seals the admin panel shut; this closes it the instant a lock arrives. */
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Prefs(this@AdminActivity).permLocked) {
                Toast.makeText(this@AdminActivity, "Device locked by the administrator", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // No admin panel while the device is locked — only the remote "unlock" can get in.
        if (prefs.permLocked) {
            Toast.makeText(this, "Device is locked — unlock it from the admin panel first", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_admin)

        etVersion = findViewById(R.id.etVersion)
        tvWallpaperState = findViewById(R.id.tvWallpaperState)
        appCheckList = findViewById(R.id.appCheckList)

        etVersion.setText(prefs.footerVersion.ifBlank {
            try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            }
        })

        findViewById<Button>(R.id.btnChooseWallpaper).setOnClickListener { pickWallpaper() }
        findViewById<Button>(R.id.btnRemoveWallpaper).setOnClickListener { removeWallpaper() }
        findViewById<Button>(R.id.btnSaveAdmin).setOnClickListener { save() }

        findViewById<Button>(R.id.btnTakeAdmin).setOnClickListener { takeAdminAccess() }
        findViewById<Button>(R.id.btnEditSetup).setOnClickListener {
            startActivity(
                Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_EDIT, true)
            )
        }
        findViewById<Button>(R.id.btnExitMdm).setOnClickListener { exitMdm() }
        findViewById<Button>(R.id.btnFactoryReset).setOnClickListener { confirmFactoryReset() }

        setupVolumeControl()
        updateAdminStatus()
        updateWallpaperState()
        buildAppList()
    }

    /** Lets the admin set the media volume even though students can't (restriction is lifted here). */
    private fun setupVolumeControl() {
        Kiosk.allowVolumeForAdmin(this)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val seek = findViewById<SeekBar>(R.id.seekVolume)
        seek.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        seek.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    try {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                    } catch (e: Exception) {
                        // ignored
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized && prefs.permLocked) { finish(); return }
        try {
            val filter = IntentFilter(LauncherActivity.ACTION_LOCK_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(lockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(lockReceiver, filter)
            }
        } catch (e: Exception) { /* ignore */ }
        if (::prefs.isInitialized) updateAdminStatus()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(lockReceiver) } catch (e: Exception) { /* not registered */ }
    }

    // ── Admin & exit ───────────────────────────────────────────────────

    private fun updateAdminStatus() {
        findViewById<TextView>(R.id.tvAdminStatus).text = when {
            Kiosk.isDeviceOwner(this) ->
                "Device Owner — Home/Recents hidden, notification & quick-settings panels blocked."
            Kiosk.isAdminActive(this) ->
                "Admin active. Full lockdown needs Device Owner (see README/adb command)."
            else -> "No admin yet — tap “Take admin access”."
        }
    }

    private fun takeAdminAccess() {
        if (Kiosk.isAdminActive(this)) {
            Toast.makeText(this, "Admin access is already active", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                MdmDeviceAdminReceiver.component(this@AdminActivity),
            )
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_add_explanation),
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open admin screen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exitMdm() {
        Kiosk.exitToSystemLauncher(this)
        // Also drop this task from Recents so SureMDM isn't left in the app-switcher after exiting.
        finishAndRemoveTask()
    }

    /** Factory reset from the control panel, with two confirmations so it can't fire by accident. */
    private fun confirmFactoryReset() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Factory reset device?")
            .setMessage("This erases everything on the tablet and returns it to factory settings. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                android.app.AlertDialog.Builder(this)
                    .setTitle("Are you absolutely sure?")
                    .setMessage("All data will be permanently wiped and the device will restart as new.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset now") { _, _ ->
                        val error = Kiosk.factoryReset(this)
                        if (error != null) {
                            Toast.makeText(this, "Reset failed — $error", Toast.LENGTH_LONG).show()
                        }
                    }
                    .show()
            }
            .show()
    }

    // ── Version + pinned apps are saved together ───────────────────────

    private fun save() {
        prefs.footerVersion = etVersion.text.toString().trim()
        val pinned = checkBoxes.filterValues { it.isChecked }.keys.toSet()
        prefs.pinnedApps = pinned

        val newPin = findViewById<EditText>(R.id.etPin).text.toString().trim()
        if (newPin.isNotEmpty()) {
            if (newPin.length == 6 && newPin.all { it.isDigit() }) {
                prefs.adminPin = newPin
            } else {
                Toast.makeText(this, "PIN must be 6 digits — not changed", Toast.LENGTH_LONG).show()
                return
            }
        }

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ── App list ───────────────────────────────────────────────────────

    private fun buildAppList() {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val currentlyPinned = prefs.pinnedApps.ifEmpty {
            AppConfig.MANAGED_APPS.map { it.packageName }.toSet()
        }
        data class Entry(val pkg: String, val label: String, val icon: Drawable)

        val apps = pm.queryIntentActivities(query, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()
            .mapNotNull { pkg ->
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    Entry(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }

        for (app in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val icon = android.widget.ImageView(this).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            }
            val check = CheckBox(this).apply {
                text = app.label
                isChecked = currentlyPinned.contains(app.pkg)
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginStart = dp(10) }
            }
            checkBoxes[app.pkg] = check
            row.addView(icon)
            row.addView(check)
            row.setOnClickListener { check.toggle() }
            appCheckList.addView(row)
        }

        if (apps.isEmpty()) {
            appCheckList.addView(TextView(this).apply {
                text = "No launchable apps found."
                setTextColor(getColor(R.color.text_secondary))
            })
        }
    }

    // ── Wallpaper ──────────────────────────────────────────────────────

    private fun wallpaperFile() = File(cacheDir, LauncherActivity.WALLPAPER_CACHE_FILE)

    private fun updateWallpaperState() {
        tvWallpaperState.text =
            if (wallpaperFile().exists()) "A custom wallpaper is set." else "Using the default background."
    }

    private fun pickWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK_IMAGE)
        } catch (e: Exception) {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" },
                REQ_PICK_IMAGE,
            )
        }
    }

    private fun removeWallpaper() {
        wallpaperFile().delete()
        updateWallpaperState()
        Toast.makeText(this, "Wallpaper removed — reopens the home screen to apply", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bitmap = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                Toast.makeText(this, "Couldn't read that image", Toast.LENGTH_SHORT).show()
                return
            }
            wallpaperFile().outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            updateWallpaperState()
            Toast.makeText(this, "Wallpaper set", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't set wallpaper: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_PICK_IMAGE = 42
    }
}
