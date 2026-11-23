package com.suguna.home

/** One of the Suguna apps shown on the home screen. */
data class ManagedApp(
    val packageName: String,
    /** Where "Update Apps" downloads the APK from (same URLs as the original MDM). */
    val downloadUrl: String,
)

object AppConfig {

    /**
     * Apps shown on the home screen (the original's launcher_apps list).
     * Like the original, only installed apps are shown, sorted by their own app name.
     * If you change these, also update <queries> in AndroidManifest.xml.
     */
    val MANAGED_APPS = listOf(
        ManagedApp(
            packageName = "com.example.suguna_student_app",
            downloadUrl = "https://z7imobileapps.s3.ap-south-1.amazonaws.com/student.apk",
        ),
        ManagedApp(
            packageName = "com.suguna.suguna_assignment_app",
            downloadUrl = "https://z7imobileapps.s3.ap-south-1.amazonaws.com/assignment.apk",
        ),
        ManagedApp(
            packageName = "com.example.suguna_exam_app",
            downloadUrl = "https://z7imobileapps.s3.ap-south-1.amazonaws.com/exam.apk",
        ),
    )

    /**
     * Packages always allowed to run under the kiosk lock-task, on top of the launcher and the
     * managed apps. Galaxy Store was being blocked by lock-task on some tablets; allowlisting it
     * (and the package installers it hands off to) lets it open, browse and install normally.
     * Unknown/not-installed packages here are harmless — lock-task just ignores them.
     */
    val ALWAYS_ALLOWED_PACKAGES = listOf(
        "com.sec.android.app.samsungapps",       // Galaxy Store
        "com.android.vending",                   // Play Store (if present)
        "com.android.packageinstaller",          // system installer UI
        "com.google.android.packageinstaller",   // system installer UI (newer)
    )

    /** Server used by the "Verify" button in Device Info (same as the original). */
    const val SERVER_URL = "https://mdmapi.z7i.in"

    // ── Firebase (admin panel link) ────────────────────────────────────────
    // Public web config — safe to ship. All protection is in the Realtime Database
    // security rules, not in hiding these values. The device signs in anonymously and
    // can only touch its own /devices/<uid> record; only the admin login reads the fleet.
    const val FIREBASE_API_KEY = "AIzaSyAMKqrsk5QLaHulz36-mOgcFD1b_OResjI"
    const val FIREBASE_DB_URL = "https://suguna-admin-eee8b-default-rtdb.europe-west1.firebasedatabase.app"

    /**
     * Home screen background image. The original received this from the MDM server.
     * Put a direct image URL here and it is downloaded once and cached (same cache file
     * name as the original). Leave empty for the plain #1A1A2E background.
     */
    const val WALLPAPER_URL = ""

    /** APK URL used by "Update MDM" to update this app. Leave empty if not hosted yet. */
    const val SELF_UPDATE_URL = ""
}
