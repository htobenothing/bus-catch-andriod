# Bus Pup (Android)

Live Singapore bus arrivals on your home screen, plus a "leave now" alert timed to your walk to the stop.

## Features
- **Two widgets**: *Bus Pup · All stops* lists every saved stop; *Bus Pup · Stop* shows one stop and has a 🔔 button to turn its alert on or off.
- **Stop widget**: when you place a widget it asks which saved stop to show. Add several widgets for several stops. Each widget lists every bus number you track there with its next arrival times, plus when to leave home. Long-press a widget to switch stops (Android 12+). Resize it down to 3×1, 2×1 or even 1×1 (bus number + leave time).
- **Several bus numbers per stop**: e.g. 961 and 190; the alert targets whichever catchable bus comes first.
- **Leave-home alerts** that fire even when the app is closed, once a day per bus, only inside the alert window you set (e.g. 07:00–09:00 on weekdays).
- **Live data** from LTA DataMall via arrivelah (no API key needed). Shows crowding and whether the bus is GPS-tracked.
- Refreshes every 15 minutes in the background and about every minute while an alert window is active.

## Versions and updates
The version lives in `version.properties` (semantic versioning, see CHANGELOG.md). Pushing a new version publishes a GitHub Release `vX.Y.Z`; the app checks for it, shows **Update now**, downloads it and opens the installer. Pushes without a version bump only build.

Releases are signed with `signing/buscatch-release.jks`. Its password must be saved as the `KEYSTORE_PASSWORD` Actions secret.

## Build the APK (no Android Studio needed)
1. Create a new repository on GitHub and upload this folder's contents (or push with git).
2. Open the repo's **Actions** tab. The "Build APK" workflow runs automatically on each push (or click **Run workflow**).
3. When it finishes (about 5 minutes), open the run and download **BusPup-apk** from Artifacts. Unzip it to get `BusPup.apk`.

Or open the folder in Android Studio and press Run.

## Install on your phone
1. Copy `BusPup.apk` to your phone (or download it there directly from GitHub).
2. Tap it. Android will ask you to allow installs from that app (Chrome or Files). Allow it once.
3. Open Bus Pup, allow notifications and exact alarms when prompted, and add your bus.
4. Long-press your home screen → **Widgets** → **Bus Pup**, drag it out and pick a stop. Repeat for each stop you want.

### Tips
- Some phones (Xiaomi, Oppo, Vivo, Huawei, Samsung) aggressively stop background apps. If alerts arrive late, set Bus Pup's battery usage to **Unrestricted** in Settings → Apps → Bus Pup → Battery.
- The APK is signed with a debug key, so it's for personal use, not the Play Store.
