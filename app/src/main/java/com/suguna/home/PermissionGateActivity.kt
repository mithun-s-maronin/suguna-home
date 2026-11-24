package com.suguna.home

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * One-time gate shown after the v36 update, when the new permissions (location, usage access,
 * overlay) aren't granted yet — the app was already set up under v34, so the normal setup flow is
 * skipped. Location is auto-granted by Device Owner; usage access and overlay need a tap each.
 * Once everything is granted this screen finishes itself and never appears again.
 */
class PermissionGateActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leaveKiosk()

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0D1B2A")) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24)
            setPadding(p, dp(40), p, p)
        }
        scroll.addView(root)
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        leaveKiosk()
        if (Perms.allGranted(this)) { skipped = false; finish(); return }
        build()
    }

    /** Fully drop the kiosk while this screen is open, so any Settings page can actually open. */
    private fun leaveKiosk() {
        try { stopLockTask() } catch (e: Exception) {}
        Kiosk.allowSettingsInLockTask(this, true)
    }

    // Back always escapes to the home screen and won't pop up again until the next reboot.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { skipped = true; finish() }

    private fun build() {
        root.removeAllViews()
        root.addView(title("Finish setup"))
        root.addView(sub("Grant this so the admin panel can see which app is open. " +
            "You'll only do this once."))

        row("Usage access (current app)", Perms.hasUsageAccess(this)) {
            toast("Find \"Sure MDM\" in the list and turn it on")
            open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        val done = Perms.allGranted(this)
        root.addView(Button(this).apply {
            text = if (done) "Done — go to home" else "Skip for now — go to home"
            setOnClickListener { skipped = true; finish() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(24); layoutParams = lp
        })
    }

    companion object {
        /** Set when the user skips the gate, so pressing Home can't reopen it in a loop.
         *  Resets on process restart (reboot), so it prompts again next boot until granted. */
        @JvmField var skipped = false
    }

    private fun row(label: String, granted: Boolean, onTap: () -> Unit) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(14); layoutParams = lp
        }
        line.addView(TextView(this).apply {
            text = (if (granted) "✓  " else "✗  ") + label
            setTextColor(if (granted) Color.parseColor("#2ECC71") else Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!granted) {
            line.addView(Button(this).apply {
                text = "Grant"
                setOnClickListener { onTap() }
            })
        }
        root.addView(line)
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e2: Exception) {}
        }
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 24f
    }

    private fun sub(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#9FB3C8")); textSize = 14f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8); layoutParams = lp
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        build()
    }
}
