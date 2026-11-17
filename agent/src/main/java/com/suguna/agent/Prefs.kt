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
}
