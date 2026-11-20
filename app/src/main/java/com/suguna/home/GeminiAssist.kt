package com.suguna.home

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Side-button (power) long-press → Gemini with "Ask about screen", while the kiosk is locked.
 *
 * Two paths, so it works whichever way the tablet's side button is set up:
 *
 *  1. Native — on current One UI, holding the side button opens the default digital assistant
 *     (Gemini) with screen context. Lock-task only lets allowlisted apps run, so while this feature
 *     is on, the Google + Gemini apps are added to the kiosk allowlist (Kiosk.lockTaskAllowlist).
 *
 *  2. Fallback — an app can't read the power key itself, but when it is held the system closes
 *     system dialogs with a reason: "globalactions" (power menu requested — the kiosk keeps that
 *     menu disabled) or "assist" (it is opening the assistant). On either, GeminiAssistActivity is
 *     launched: a see-through activity that calls Activity.showAssist(), which opens the assistant
 *     with a screenshot of what's on screen — the same context the side button gives it.
 *     For "assist" it waits first and skips if Gemini already came up, and the activity only fires
 *     when it gets window focus, so Gemini is never opened twice.
 */
object GeminiAssist {

    private const val TAG = "GeminiAssist"
    private const val EXTRA_REASON = "reason"
    private const val REASON_POWER_MENU = "globalactions"
    private const val REASON_ASSIST = "assist"

    /** How long to give the system's own assistant launch before stepping in. */
    private const val NATIVE_GRACE_MS = 1500L
    private const val DEBOUNCE_MS = 3000L

    /** Google app (hosts the Gemini overlay) and the Gemini app. */
    val PACKAGES = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.bard",
    )

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastTriggerAt = 0L

    /** Starts listening for side-button long-presses. Call from a long-lived component. */
    fun register(context: Context): BroadcastReceiver? {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                onSystemDialogsClosed(c.applicationContext, intent.getStringExtra(EXTRA_REASON))
            }
        }
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        return try {
            // EXPORTED because SystemUI (not the system uid) sends some of these, and a
            // not-exported receiver would miss them. Normal apps can't send it on Android 12+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiver
        } catch (e: Exception) {
            Log.w(TAG, "register failed: ${e.message}")
            null
        }
    }

    fun unregister(context: Context, receiver: BroadcastReceiver?) {
        if (receiver == null) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // not registered
        }
    }

    private fun onSystemDialogsClosed(ctx: Context, reason: String?) {
        val label = when (reason) {
            REASON_POWER_MENU -> "power menu"
            REASON_ASSIST -> "assistant"
            else -> return
        }
        val prefs = Prefs(ctx)
        // Shown in Admin so you can check the long-press actually reaches the app.
        prefs.geminiLastSignal =
            "$label · " + SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date())

        if (!prefs.geminiShortcut || prefs.permLocked) return
        // Outside the kiosk the side button keeps its normal behaviour (power menu stays usable).
        if (!isKioskLocked(ctx)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < DEBOUNCE_MS) return
        lastTriggerAt = now

        if (reason == REASON_ASSIST) {
            // The system is opening the assistant itself — only step in if that didn't happen.
            main.postDelayed({ if (!geminiCameToFront(ctx)) launch(ctx) }, NATIVE_GRACE_MS)
        } else {
            launch(ctx)
        }
    }

    /** Opens Gemini over whatever is on screen. Also used by the Admin "Test" button. */
    fun launch(context: Context) {
        try {
            context.startActivity(
                Intent(context, GeminiAssistActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "launch failed: ${e.message}")
        }
    }

    private fun isKioskLocked(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    } catch (e: Exception) {
        false
    }

    /**
     * True if a Google/Gemini screen came to the front in the last few seconds, i.e. the side
     * button already opened Gemini as a full screen. Needs usage access; without it returns false
     * (GeminiAssistActivity's focus check still prevents a double open of the overlay).
     */
    @Suppress("DEPRECATION") // checkOpNoThrow (pre-Q) and MOVE_TO_FOREGROUND (= ACTIVITY_RESUMED)
    private fun geminiCameToFront(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val uid = Process.myUid()
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, ctx.packageName)
            } else {
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, ctx.packageName)
            }
            if (mode != AppOpsManager.MODE_ALLOWED) return false
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5_000L, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val cameToFront = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                if (cameToFront && event.packageName in PACKAGES) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
