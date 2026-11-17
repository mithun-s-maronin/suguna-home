package com.suguna.agent

import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * One-time setup for the agent: enter name + enrollment, grant the permissions the admin panel
 * needs (device admin, location, usage access, overlay), then start the service. After that it
 * runs in the background; opening it again just shows the status.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        val etName = findViewById<EditText>(R.id.etName)
        val etEnroll = findViewById<EditText>(R.id.etEnroll)
        status = findViewById(R.id.status)
        etName.setText(prefs.studentName)
        etEnroll.setText(prefs.enrollmentNumber)

        findViewById<Button>(R.id.btnActivate).setOnClickListener { activateAdmin() }
        findViewById<Button>(R.id.btnLocation).setOnClickListener { grantLocation() }
        findViewById<Button>(R.id.btnUsage).setOnClickListener { openUsageAccess() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { grantOverlay() }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.studentName = etName.text.toString().trim()
            prefs.enrollmentNumber = etEnroll.text.toString().trim()
            AgentService.start(this)
            Toast.makeText(this, "Saved — agent running", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        // Keep the agent alive whenever the setup screen is opened.
        AgentService.start(this)
        refreshStatus()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    // ---- permission state --------------------------------------------------------------------

    private fun isAdmin(): Boolean =
        getSystemService(DevicePolicyManager::class.java)
            .isAdminActive(AgentAdminReceiver.component(this))

    private fun hasLocation(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasUsage(): Boolean = try {
        val ops = getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    // ---- grant flows -------------------------------------------------------------------------

    private fun activateAdmin() {
        if (isAdmin()) { Toast.makeText(this, "Already active", Toast.LENGTH_SHORT).show(); return }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AgentAdminReceiver.component(this@MainActivity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets the Suguna Agent lock the device remotely from the admin panel.",
            )
        }
        safeStart(intent, "admin screen")
    }

    private fun grantLocation() {
        if (hasLocation()) { Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show(); return }
        requestPermissions(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ), 101
        )
    }

    private fun openUsageAccess() {
        if (hasUsage()) { Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Find \"Suguna Agent\" and turn it on", Toast.LENGTH_LONG).show()
        safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), "usage-access screen")
    }

    private fun grantOverlay() {
        if (hasOverlay()) { Toast.makeText(this, "Already granted", Toast.LENGTH_SHORT).show(); return }
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        safeStart(i, "overlay screen")
    }

    private fun safeStart(intent: Intent, what: String) {
        try { startActivity(intent) } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open $what: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun refreshStatus() {
        status.text = buildString {
            append("${mark(isAdmin())} Device admin  (remote Lock)\n")
            append("${mark(hasLocation())} Location  (GPS on map)\n")
            append("${mark(hasUsage())} Usage access  (current app)\n")
            append("${mark(hasOverlay())} Display over apps  (permanent lock)\n\n")
            append(
                if (isAdmin()) "Monitoring is on; this device shows in the admin panel."
                else "Tap each ✗ above to finish setup."
            )
        }
    }
}
