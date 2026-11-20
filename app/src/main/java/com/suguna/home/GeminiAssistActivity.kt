package com.suguna.home

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast

/**
 * See-through, single-purpose activity that opens the default assistant (Gemini) over whatever is
 * on screen, with screen context — so Gemini's "Ask about screen" sees the app underneath.
 *
 * Activity.showAssist() only works for the current top, visible activity, which is why this exists:
 * it is started in its own task on top of the current app, draws nothing, and once it has window
 * focus asks for the assistant. The system takes the screenshot during that call (this window is
 * transparent, so it captures the app below) and opens Gemini. As soon as Gemini's window takes
 * focus this activity finishes, so it never sits over the app blocking touches.
 *
 * If it never gets focus, another window (e.g. Gemini already opened by the side button) is on
 * top, so it quietly finishes without opening anything.
 */
class GeminiAssistActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var invoked = false
    private var closing = false
    private val closeTask = Runnable { close() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No content view: the window stays fully transparent.
        handler.postDelayed(closeTask, NO_FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (closing) return
        if (hasFocus && !invoked) {
            handler.removeCallbacks(closeTask)
            handler.postDelayed({ invoke(1) }, SETTLE_MS)
        } else if (!hasFocus && invoked) {
            // Gemini's window is up and has focus. The screenshot was already taken during
            // showAssist(), so get out of the way of the app underneath.
            handler.removeCallbacks(closeTask)
            handler.postDelayed(closeTask, LINGER_MS)
        }
    }

    private fun invoke(attempt: Int) {
        if (closing || invoked) return
        if (!hasWindowFocus()) {
            // Something else (e.g. Gemini opened by the side button) took the screen meanwhile.
            close()
            return
        }
        val shown = try {
            showAssist(Bundle().apply {
                putInt(INVOCATION_TYPE_KEY, INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS)
            })
        } catch (e: Exception) {
            Log.w(TAG, "showAssist failed: ${e.message}")
            false
        }
        when {
            shown -> {
                invoked = true
                // If the assistant never takes focus, don't linger anyway.
                handler.postDelayed(closeTask, ASSIST_WAIT_MS)
            }
            attempt < MAX_ATTEMPTS -> handler.postDelayed({ invoke(attempt + 1) }, RETRY_MS)
            else -> {
                Toast.makeText(
                    applicationContext,
                    "Gemini couldn't open. Set Gemini as the default digital assistant app.",
                    Toast.LENGTH_LONG,
                ).show()
                close()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) close()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        close()
    }

    override fun onStop() {
        super.onStop()
        close()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // overridePendingTransition: still the way to skip the exit animation
    private fun close() {
        if (closing) return
        closing = true
        handler.removeCallbacksAndMessages(null)
        finish()
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val TAG = "GeminiAssist"

        /** Same bundle key/value the system uses for a power-button invocation (AssistUtils). */
        const val INVOCATION_TYPE_KEY = "invocation_type"
        const val INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6

        const val NO_FOCUS_TIMEOUT_MS = 1500L
        const val SETTLE_MS = 120L
        const val RETRY_MS = 250L
        const val MAX_ATTEMPTS = 3
        const val ASSIST_WAIT_MS = 2500L
        const val LINGER_MS = 400L
    }
}
