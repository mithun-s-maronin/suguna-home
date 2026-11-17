package com.suguna.agent

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * One-time setup for the agent: enter name + enrollment, activate device admin, start the service.
 * After that it runs in the background; opening it again just shows the status.
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

    private fun isAdmin(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return dpm.isAdminActive(AgentAdminReceiver.component(this))
    }

    private fun activateAdmin() {
        if (isAdmin()) { Toast.makeText(this, "Already active", Toast.LENGTH_SHORT).show(); return }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, AgentAdminReceiver.component(this@MainActivity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets the Suguna Agent lock the device remotely from the admin panel.",
            )
        }
        try { startActivity(intent) } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open admin screen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshStatus() {
        status.text = if (isAdmin())
            "✓ Device admin active — remote Lock works.\nMonitoring is on; this device shows in the admin panel."
        else
            "⚠ Not a device admin yet — tap \"Activate device admin\" so remote Lock works.\n(Monitoring still works without it.)"
    }
}
