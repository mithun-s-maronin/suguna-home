package com.suguna.agent

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("suguna_agent", Context.MODE_PRIVATE)

    var studentName: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v).apply()

    var enrollmentNumber: String
        get() = sp.getString("enrollment", "") ?: ""
        set(v) = sp.edit().putString("enrollment", v).apply()

    /** True while under a remote lock (full-screen) that only the admin panel can release. */
    var permLocked: Boolean
        get() = sp.getBoolean("perm_locked", false)
        set(v) = sp.edit().putBoolean("perm_locked", v).apply()

    /** Firebase anonymous-auth refresh token (persists this device's identity). */
    var fbRefreshToken: String
        get() = sp.getString("fb_refresh", "") ?: ""
        set(v) = sp.edit().putString("fb_refresh", v).apply()

    /** This device's Firebase uid — its record id in /devices. */
    var fbUid: String
        get() = sp.getString("fb_uid", "") ?: ""
        set(v) = sp.edit().putString("fb_uid", v).apply()
}
