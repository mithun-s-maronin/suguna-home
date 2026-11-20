package com.suguna.home

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Kiosk control for the launcher, using Android lock-task mode. There is no screen-pinning for a
 * normal app and no pin/unpin UI — the lockdown only engages when the app is Device Owner, where
 * it is silent (no "pinned" toast, no escape gesture).
 *
 * As Device Owner, lock-task features are chosen so that:
 *   - the status bar stays as it is,
 *   - the notification shade and quick-settings panel can't be pulled down,
 *   - the Home and Recents (app-switcher) buttons are hidden, and
 *   - the Back button stays.
 *
 * Provision Device Owner once over USB (device with no accounts):
 *   adb shell dpm set-device-owner com.suguna.home/.MdmDeviceAdminReceiver
 */
object Kiosk {

    private const val TAG = "Kiosk"
    private const val HOME_ALIAS = "com.suguna.home.HomeAlias"

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    fun isAdminActive(context: Context): Boolean =
        dpm(context).isAdminActive(MdmDeviceAdminReceiver.component(context))

    /**
     * Engage the kiosk lockdown. Does nothing unless the app is Device Owner, so a normally
     * installed app behaves like an ordinary launcher (no pinning).
     */
    fun applyKiosk(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        val dpm = dpm(activity)
        val admin = MdmDeviceAdminReceiver.component(activity)

        try {
            dpm.setLockTaskPackages(admin, lockTaskAllowlist(activity))
        } catch (e: Exception) {
            Log.w(TAG, "setLockTaskPackages failed: ${e.message}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Status bar stays present but EMPTY: omit SYSTEM_INFO so no clock, battery,
                // signal or Wi-Fi icons are shown (matches the original). Omit NOTIFICATIONS so the
                // shade / quick settings can't be opened, omit HOME and OVERVIEW so those two nav
                // buttons are hidden, and omit GLOBAL_ACTIONS so the power-off menu is disabled.
                // Back is always available in lock-task.
                // NONE (not KEYGUARD): while locked into the app the keyguard is suppressed, so
                // waking the tablet goes straight to the app, not the lock screen. The device's own
                // screen-lock password is untouched and works again once the user exits the MDM.
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_NONE,
                )
            } catch (e: Exception) {
                Log.w(TAG, "setLockTaskFeatures failed: ${e.message}")
            }
        }
        // Block the volume slider everywhere (including pinned apps). The admin screen lifts this
        // so the volume level can still be set there.
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME)
        } catch (e: Exception) {
            Log.w(TAG, "DISALLOW_ADJUST_VOLUME failed: ${e.message}")
        }
        // Make it harder to copy/back up the APK: block ADB/USB debugging (adb pull / adb backup)
        // and block installing other apps (e.g. an "APK extractor"). allowBackup is already false.
        for (restriction in arrayOf(
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        )) {
            try {
                dpm.addUserRestriction(admin, restriction)
            } catch (e: Exception) {
                Log.w(TAG, "restriction $restriction failed: ${e.message}")
            }
        }
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                activity.startLockTask()
            }
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask failed: ${e.message}")
        }
    }

    /**
     * Apps lock-task lets run: the launcher AND the apps on the home screen (otherwise lock-task
     * blocks them from opening), Galaxy Store + installers, and — while the Gemini side-button
     * shortcut is on — the Google/Gemini apps, so the assistant can open over the kiosk.
     */
    fun lockTaskAllowlist(context: Context): Array<String> {
        val prefs = Prefs(context)
        val allow = linkedSetOf(context.packageName)
        allow.addAll(AppConfig.MANAGED_APPS.map { it.packageName })
        allow.addAll(prefs.pinnedApps)
        allow.addAll(AppConfig.ALWAYS_ALLOWED_PACKAGES)
        if (prefs.geminiShortcut) allow.addAll(GeminiAssist.PACKAGES)
        return allow.toTypedArray()
    }

    /** Re-apply the lock-task allowlist right away (e.g. after the Gemini switch changes). */
    fun refreshLockTaskPackages(context: Context) {
        if (!isDeviceOwner(context)) return
        try {
            dpm(context).setLockTaskPackages(
                MdmDeviceAdminReceiver.component(context),
                lockTaskAllowlist(context),
            )
        } catch (e: Exception) {
            Log.w(TAG, "setLockTaskPackages failed: ${e.message}")
        }
    }

    /**
     * Wipe the device after too many wrong admin-PIN attempts.
     * Returns null when the wipe was initiated, or a short reason string when it could not run.
     *
     * On Android 14 devices in headless system-user mode, wipeData() tries to remove User 0 and
     * fails ("User 0 is a system user and cannot be removed"); wipeDevice() factory-resets the
     * whole device instead. wipeDevice() is API 34, so it is called by reflection (the build SDK is
     * 33), with wipeData() as the fallback for older devices.
     */
    fun factoryReset(context: Context): String? {
        val dpm = dpm(context)
        if (!dpm.isDeviceOwnerApp(context.packageName) && !dpm.isProfileOwnerApp(context.packageName)) {
            return "not device owner"
        }
        val admin = MdmDeviceAdminReceiver.component(context)
        try {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        } catch (e: Exception) {
            Log.w(TAG, "clear DISALLOW_FACTORY_RESET: ${e.message}")
        }

        var firstError: String? = null

        // Preferred on Android 14+: wipeDevice(int) factory-resets the whole device.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val method = DevicePolicyManager::class.java
                    .getMethod("wipeDevice", Int::class.javaPrimitiveType)
                method.invoke(dpm, 0)
                return null
            } catch (e: Throwable) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                Log.e(TAG, "wipeDevice failed", cause)
                firstError = "${cause.javaClass.simpleName}: ${cause.message ?: "unknown"}"
            }
        }

        // Fallback (and the normal path pre-Android-14).
        return try {
            dpm.wipeData(0)
            null
        } catch (e: Exception) {
            Log.e(TAG, "wipeData failed", e)
            val wipeDataErr = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            if (firstError != null) "$firstError | $wipeDataErr" else wipeDataErr
        }
    }

    /**
     * Lift the volume-adjust restriction so the admin screen can set the level. The restriction is
     * re-applied automatically the next time the home screen comes to the front (applyKiosk).
     */
    fun allowVolumeForAdmin(context: Context) {
        if (!isDeviceOwner(context)) return
        try {
            dpm(context).clearUserRestriction(
                MdmDeviceAdminReceiver.component(context),
                UserManager.DISALLOW_ADJUST_VOLUME,
            )
        } catch (e: Exception) {
            Log.w(TAG, "allowVolumeForAdmin failed: ${e.message}")
        }
    }

    // ── Home switching for "Exit MDM" ──────────────────────────────────

    private fun homeAlias(context: Context) = ComponentName(context.packageName, HOME_ALIAS)

    fun enableMdmHome(context: Context) {
        context.packageManager.setComponentEnabledSetting(
            homeAlias(context),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun disableMdmHome(context: Context) {
        context.packageManager.setComponentEnabledSetting(
            homeAlias(context),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * As Device Owner, lock this app in as the persistent default home launcher — no "pick a home
     * app" dialog, and it survives reboots. Cleared again on Exit MDM.
     */
    fun setPersistentHome(context: Context) {
        if (!isDeviceOwner(context)) return
        enableMdmHome(context)
        val dpm = dpm(context)
        val admin = MdmDeviceAdminReceiver.component(context)
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, homeAlias(context))
        } catch (e: Exception) {
            Log.w(TAG, "setPersistentHome failed: ${e.message}")
        }
    }

    fun clearPersistentHome(context: Context) {
        if (!isDeviceOwner(context)) return
        try {
            dpm(context).clearPackagePersistentPreferredActivities(
                MdmDeviceAdminReceiver.component(context),
                context.packageName,
            )
        } catch (e: Exception) {
            Log.w(TAG, "clearPersistentHome failed: ${e.message}")
        }
    }

    /** Device Owner forces itself in as home, so "default home" always counts as handled there. */
    fun isHomeHandled(context: Context): Boolean =
        isDeviceOwner(context) || SetupPermissions.isDefaultHome(context)

    /** Leave the MDM: end lock-task and hand the home screen back to the stock (One UI) launcher. */
    fun exitToSystemLauncher(activity: Activity) {
        if (isDeviceOwner(activity)) {
            val dpm = dpm(activity)
            val admin = MdmDeviceAdminReceiver.component(activity)
            try {
                val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) activity.stopLockTask()
            } catch (e: Exception) {
                Log.w(TAG, "stopLockTask failed: ${e.message}")
            }
            try {
                dpm.setLockTaskPackages(admin, emptyArray())
            } catch (e: Exception) {
                Log.w(TAG, "clear lock task packages failed: ${e.message}")
            }
            for (restriction in arrayOf(
                UserManager.DISALLOW_ADJUST_VOLUME,
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            )) {
                try {
                    dpm.clearUserRestriction(admin, restriction)
                } catch (e: Exception) {
                    Log.w(TAG, "clear $restriction failed: ${e.message}")
                }
            }
        }
        clearPersistentHome(activity)
        disableMdmHome(activity)
        // With our HOME alias disabled, the stock launcher is the only remaining home.
        try {
            activity.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Launch stock home failed: ${e.message}")
        }
        // Remove SureMDM from Recents / the app-switcher so it isn't left running in the background.
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { it.finishAndRemoveTask() }
        } catch (e: Exception) {
            Log.w(TAG, "clear recents on exit failed: ${e.message}")
        }
    }
}
