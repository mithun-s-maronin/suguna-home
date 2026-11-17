package com.suguna.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * First screen after installing: asks for name + enrollment number and walks
 * through the permissions. Also reachable later from the home screen ("Edit setup").
 */
class SetupActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var etName: EditText
    private lateinit var etEnrollment: EditText
    private lateinit var permissionList: LinearLayout
    private lateinit var tvFinishHint: TextView
    private lateinit var btnFinish: Button

    private val permissions = SetupPermissions.all()

    /** Views for each permission row, keyed by permission id. */
    private class RowViews(val status: TextView, val button: Button)
    private val rows = mutableMapOf<String, RowViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // "Exit MDM" disables our HOME component to hand the home screen to One UI. Re-enable it
        // here so Suguna Home appears in the "default home app" list again when it is reopened.
        Kiosk.enableMdmHome(this)
        // As Device Owner, lock ourselves in as the default home now — no manual "pick home app"
        // step, so the app opens straight to its home screen from here on.
        Kiosk.setPersistentHome(this)

        // Already set up and opened from the app icon → go straight to the home screen.
        val editing = intent.getBooleanExtra(EXTRA_EDIT, false)
        if (!editing && prefs.setupComplete && SetupPermissions.missingRequired(this).isEmpty()) {
            openHome()
            return
        }

        setContentView(R.layout.activity_setup)
        etName = findViewById(R.id.etName)
        etEnrollment = findViewById(R.id.etEnrollment)
        permissionList = findViewById(R.id.permissionList)
        tvFinishHint = findViewById(R.id.tvFinishHint)
        btnFinish = findViewById(R.id.btnFinish)

        etName.setText(prefs.studentName)
        etEnrollment.setText(prefs.enrollmentNumber)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateFinishState()
        }
        etName.addTextChangedListener(watcher)
        etEnrollment.addTextChangedListener(watcher)

        buildPermissionRows()
        btnFinish.setOnClickListener { finishSetup() }
        // Dedicated Save: persists name + enrollment on its own, even if a permission is still
        // pending (the old flow only saved via Finish, which did nothing unless everything was done).
        findViewById<Button>(R.id.btnSaveSetup).setOnClickListener { saveDetails() }
    }

    private fun saveDetails() {
        val name = etName.text.toString().trim()
        val enrollment = etEnrollment.text.toString().trim()
        if (name.isEmpty()) {
            etName.error = "Please enter your name"
            return
        }
        if (enrollment.isEmpty()) {
            etEnrollment.error = "Please enter your enrollment number"
            return
        }
        prefs.studentName = name
        prefs.enrollmentNumber = enrollment
        prefs.setupComplete = true
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        // Returning from a system settings screen → re-check everything.
        if (::btnFinish.isInitialized) {
            refreshPermissionRows()
            updateFinishState()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SetupPermissions.REQUEST_HOME_ROLE &&
            !SetupPermissions.isDefaultHome(this)
        ) {
            // Dialog was dismissed or isn't supported on this phone → let them pick it manually.
            SetupPermissions.openHomeSettings(this)
        }
    }

    private fun buildPermissionRows() {
        permissionList.removeAllViews()
        rows.clear()
        for (permission in permissions) {
            val row = layoutInflater.inflate(R.layout.item_permission, permissionList, false)
            val tag = getString(if (permission.required) R.string.perm_required else R.string.perm_optional)
            row.findViewById<TextView>(R.id.tvPermTitle).text = permission.title
            row.findViewById<TextView>(R.id.tvPermDesc).text = "${permission.description} ($tag)"
            val button = row.findViewById<Button>(R.id.btnPermEnable)
            button.setOnClickListener { permission.request(this) }
            rows[permission.id] = RowViews(row.findViewById(R.id.tvPermStatus), button)
            permissionList.addView(row)
        }
        refreshPermissionRows()
    }

    private fun refreshPermissionRows() {
        for (permission in permissions) {
            val views = rows[permission.id] ?: continue
            if (permission.isGranted(this)) {
                views.status.setText(R.string.perm_enabled)
                views.status.setTextColor(getColor(R.color.ok_green))
                views.button.visibility = View.GONE
            } else {
                views.status.setText(R.string.perm_not_enabled)
                views.status.setTextColor(getColor(R.color.warn_amber))
                views.button.visibility = View.VISIBLE
            }
        }
    }

    /** Lists what is still missing and only enables "Finish" when everything required is done. */
    private fun updateFinishState() {
        val missing = mutableListOf<String>()
        if (etName.text.isBlank()) missing += "your name"
        if (etEnrollment.text.isBlank()) missing += "your enrollment number"
        SetupPermissions.missingRequired(this).forEach { missing += "\"${it.title}\"" }

        btnFinish.isEnabled = missing.isEmpty()
        tvFinishHint.text = if (missing.isEmpty()) "" else "Still needed: " + missing.joinToString(", ")
        tvFinishHint.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun finishSetup() {
        val name = etName.text.toString().trim()
        val enrollment = etEnrollment.text.toString().trim()
        if (name.isEmpty()) {
            etName.error = "Please enter your name"
            return
        }
        if (enrollment.isEmpty()) {
            etEnrollment.error = "Please enter your enrollment number"
            return
        }
        if (SetupPermissions.missingRequired(this).isNotEmpty()) {
            updateFinishState()
            return
        }

        prefs.studentName = name
        prefs.enrollmentNumber = enrollment
        prefs.setupComplete = true
        openHome()
    }

    private fun openHome() {
        startActivity(
            Intent(this, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    companion object {
        /** Pass true to open the setup screen for editing even when setup is already done. */
        const val EXTRA_EDIT = "com.suguna.home.EXTRA_EDIT"
    }
}
