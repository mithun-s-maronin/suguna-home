# Suguna Home

Recreation of the original Sure MDM home screen ("Suguna Home") as a normal home-screen app.
On first open it asks for the student's name and enrollment number and walks through the
permissions; after that the home screen is the original launcher screen.

## Build & install
1. Open this folder in Android Studio (File → Open) and let Gradle sync.
2. Run ▶ on a connected phone/tablet, or Build → Build APK(s).

## Where things are
| What | File |
|---|---|
| Home screen layout (copied from the original) | `res/layout/activity_launcher.xml`, `item_launcher_app.xml` |
| Device Info dialog (copied from the original) | `res/layout/dialog_device_info.xml` |
| Home screen logic (ported from the original LauncherActivity) | `LauncherActivity.kt` |
| The three apps + their APK URLs, wallpaper URL, update URL | `AppConfig.kt` |
| Permission checklist on the setup screen | `SetupPermissions.kt` |

## Wallpaper
The original received its background image from the MDM server. Set `AppConfig.WALLPAPER_URL`
to a direct image link (or ask to bundle the image into the app).

## Admin (customize)
Long-press the footer ("Managed Device · v…") on the home screen to open the admin screen:
pin which apps show on the home screen, set a wallpaper image, and edit the version number.

## Gemini on the side button
Holding the side (power) button opens Gemini with **Ask about screen**, even inside the kiosk.
One UI does the long-press itself (Settings → Advanced features → Side button → **Long press →
Digital assistant**); while Admin → "Gemini on the side button" is on, the Google + Gemini apps are
allowlisted in lock-task (`AppConfig.GEMINI_PACKAGES`) so the kiosk doesn't block it. Needs Gemini
as the default digital assistant app (screen/screenshot access on) and a signed-in Google account.

A v36 test build also had an app-side fallback (power-menu signal listener + see-through activity
calling `Activity.showAssist()`); Play Protect flagged that build as harmful, so it was removed.

## Wi-Fi panel
The in-app Wi-Fi panel is recreated from the original (`WifiConnectActivity`). It needs the
location permission to list networks (Android requirement). On Android 11+, tapping Connect
hands the network to the system "Add network" dialog, since a non-Device-Owner app can't join
Wi-Fi silently.

## Hiding Home & Recents
The launcher pins itself so only the **Home** and **Recents** buttons are removed — **Back** and
the **status bar** stay visible. The admin screen (long-press the footer) is unchanged from v6.
For the exact result (status bar kept, no escape) the app must be Device Owner, set once over USB:
`adb shell dpm set-device-owner com.suguna.home/.MdmDeviceAdminReceiver`
Without Device Owner it falls back to Android screen pinning, which also hides Home and Recents.
