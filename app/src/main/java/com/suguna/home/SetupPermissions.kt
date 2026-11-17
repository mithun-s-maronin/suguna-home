package com.suguna.home

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * One row in the setup checklist.
 *
 * To add another permission later (location, notifications, …), add a new
 * SetupPermission to [SetupPermissions.all] — the setup screen builds its rows from that list.
 */
class SetupPermission(
    val id: String,
    val title: String,
    val description: String,
    val required: Boolean,
    val isGranted: (Context) -> Boolean,
    val request: (Activity) -> Unit,
)

object SetupPermissions {

    const val REQUEST_HOME_ROLE = 1001

    fun all(): List<SetupPermission> = listOf(
        SetupPermission(
            id = "default_home",
            title = "Set as default home screen",
            description = "Makes Sure MDM open whenever you press the Home button.",
            required = true,
            // Device Owner claims the home role itself (setPersistentHome), so it's always handled
            // there; a normal install still needs the system dialog.
            isGranted = { Kiosk.isHomeHandled(it) },
            request = { requestDefaultHome(it) },
        ),
        SetupPermission(
            id = "install_apps",
            title = "Allow installing the student apps",
            description = "Used by \"Update Apps\" in Device Info to install the student apps.",
            required = false,
            isGranted = { it.packageManager.canRequestPackageInstalls() },
            request = { openInstallUnknownApps(it) },
        ),
        SetupPermission(
            id = "write_settings",
            title = "Allow changing brightness",
            description = "Lets the swipe on the right edge of the home screen save the screen brightness.",
            required = false,
            isGranted = { Settings.System.canWrite(it) },
            request = {
                openFirstAvailable(
                    it,
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + it.packageName)),
                    Intent(Settings.ACTION_SETTINGS),
                )
            },
        ),
    )


    fun missingRequired(context: Context): List<SetupPermission> =
        all().filter { it.required && !it.isGranted(context) }

    // ── Default home app ────────────────────────────────────────────────

    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager
            .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /** Android 10+: shows the system "Set Sure MDM as default?" dialog. Older: opens Home settings. */
    fun requestDefaultHome(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                try {
                    activity.startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                        REQUEST_HOME_ROLE,
                    )
                    return
                } catch (e: ActivityNotFoundException) {
                    // Fall through to the settings screen
                }
            }
        }
        openHomeSettings(activity)
    }

    /** Fallback when the dialog isn't available (some phones, or after the user declined twice). */
    fun openHomeSettings(activity: Activity) {
        openFirstAvailable(
            activity,
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    // ── Install unknown apps ───────────────────────────────────────────

    fun openInstallUnknownApps(activity: Activity) {
        openFirstAvailable(
            activity,
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.packageName),
            ),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    fun openFirstAvailable(activity: Activity, vararg intents: Intent) {
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            }
        }
    }
}
