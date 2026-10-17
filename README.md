# Bus Catch (Android)

Live Singapore bus arrivals on your home screen, plus a "leave now" alert timed to your walk to the stop.

## Features
- **Home-screen widget** showing up to 3 saved stops (each can track several bus numbers): next arrival times and when to leave home. Tap ⟳ to refresh, tap anywhere else to open the app.
- **Leave-home alerts** that fire even when the app is closed, once a day per bus, only inside the alert window you set (e.g. 07:00–09:00 on weekdays).
- **Live data** from LTA DataMall via arrivelah (no API key needed). Shows crowding and whether the bus is GPS-tracked.
- Refreshes every 15 minutes in the background and about every minute while an alert window is active.

## Build the APK (no Android Studio needed)
1. Create a new repository on GitHub and upload this folder's contents (or push with git).
2. Open the repo's **Actions** tab. The "Build APK" workflow runs automatically on each push (or click **Run workflow**).
3. When it finishes (about 5 minutes), open the run and download **BusCatch-apk** from Artifacts. Unzip it to get `BusCatch.apk`.

Or open the folder in Android Studio and press Run.

## Install on your phone
1. Copy `BusCatch.apk` to your phone (or download it there directly from GitHub).
2. Tap it. Android will ask you to allow installs from that app (Chrome or Files). Allow it once.
3. Open Bus Catch, allow notifications and exact alarms when prompted, and add your bus.
4. Long-press your home screen → **Widgets** → **Bus Catch** and drag it out.

### Tips
- Some phones (Xiaomi, Oppo, Vivo, Huawei, Samsung) aggressively stop background apps. If alerts arrive late, set Bus Catch's battery usage to **Unrestricted** in Settings → Apps → Bus Catch → Battery.
- The APK is signed with a debug key, so it's for personal use, not the Play Store.
