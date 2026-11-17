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

    /** True while the device is under a *permanent* lock that only the admin panel can release. */
    var permLocked: Boolean
        get() = sp.getBoolean("perm_locked", false)
        set(v) = sp.edit().putBoolean("perm_locked", v).apply()
}
