package com.suguna.home

import android.content.Context

/** Small wrapper around SharedPreferences for the student's details. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var studentName: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_NAME, value).apply()

    var enrollmentNumber: String
        get() = sp.getString(KEY_ENROLLMENT, "") ?: ""
        set(value) = sp.edit().putString(KEY_ENROLLMENT, value).apply()

    var setupComplete: Boolean
        get() = sp.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = sp.edit().putBoolean(KEY_SETUP_COMPLETE, value).apply()

    /** "✓ Verified" badge in Device Info. */
    var deviceVerified: Boolean
        get() = sp.getBoolean(KEY_DEVICE_VERIFIED, false)
        set(value) = sp.edit().putBoolean(KEY_DEVICE_VERIFIED, value).apply()

    /** Package names of the apps pinned to the home screen. Empty = use the default three. */
    var pinnedApps: Set<String>
        get() = sp.getStringSet(KEY_PINNED_APPS, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_PINNED_APPS, value).apply()

    /** Version shown in the home-screen footer. Blank = the app's own versionName. */
    var footerVersion: String
        get() = sp.getString(KEY_FOOTER_VERSION, "") ?: ""
        set(value) = sp.edit().putString(KEY_FOOTER_VERSION, value).apply()

    /** 6-digit PIN that unlocks the admin screen. Default 000000 — change it in Admin. */
    var adminPin: String
        get() = sp.getString(KEY_ADMIN_PIN, "000000") ?: "000000"
        set(value) = sp.edit().putString(KEY_ADMIN_PIN, value).apply()

    /** Consecutive wrong PIN attempts (persists so cancelling can't reset it). */
    var pinFailCount: Int
        get() = sp.getInt(KEY_PIN_FAILS, 0)
        set(value) = sp.edit().putInt(KEY_PIN_FAILS, value).apply()

    /** Epoch millis until which PIN entry is locked out (the 30s gap before the last attempt). */
    var pinLockoutUntil: Long
        get() = sp.getLong(KEY_PIN_LOCKOUT, 0L)
        set(value) = sp.edit().putLong(KEY_PIN_LOCKOUT, value).apply()

    /** True while under a permanent remote lock that only the admin panel can release. */
    var permLocked: Boolean
        get() = sp.getBoolean(KEY_PERM_LOCKED, false)
        set(value) = sp.edit().putBoolean(KEY_PERM_LOCKED, value).apply()

    private companion object {
        const val KEY_DEVICE_VERIFIED = "device_verified"
        const val KEY_PINNED_APPS = "pinned_apps"
        const val KEY_FOOTER_VERSION = "footer_version"
        const val KEY_ADMIN_PIN = "admin_pin"
        const val KEY_PIN_FAILS = "pin_fail_count"
        const val KEY_PIN_LOCKOUT = "pin_lockout_until"
        const val KEY_PERM_LOCKED = "perm_locked"
        const val FILE_NAME = "suguna_home"
        const val KEY_NAME = "student_name"
        const val KEY_ENROLLMENT = "enrollment_number"
        const val KEY_SETUP_COMPLETE = "setup_complete"
    }
}
