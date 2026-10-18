# Changelog

All notable changes to Bus Pup (formerly Bus Catch). Versions follow [Semantic Versioning](https://semver.org).

## 1.15.0
- **Alerts rebuilt so they arrive on time**, including on Xiaomi:
  - the phone's alarm clock wakes Bus Pup at the start of each alert range to fetch live times and set the leave alarm, instead of waiting for background refresh
  - re-checks 3 minutes before you leave, in case the bus moved
  - the leave alarm is an alarm-clock alarm, so it isn't delayed by battery saving
  - alerts are rescheduled after a restart, app update or time change
- **Alarm-style alert**: rings at alarm volume (even on silent) and vibrates until you tap **I'm going**, with a full-screen Leave now page on the lock screen.
- Settings → Alerts: **Test alert in 5 seconds**, plus Xiaomi shortcuts for Autostart, battery saver and floating/lock-screen notifications.
- An alert is skipped if you'd have to leave after the range ends.

## 1.14.0
- Stop widget **Auto** mode follows alert time ranges:
  - inside a stop's range → that stop (if ranges overlap, the bus you must leave for first)
  - otherwise → the stop whose range starts next
  - stops with the alert switched off (🔕) still count; 🔕 only silences the notification
  - no usable range → "No alert times set"
- Auto switches the minute a range opens or closes, instead of up to 15 minutes later.
- Fix: ranges that cross midnight (e.g. 22:00–01:00) now stay open after midnight.

## 1.13.0
- **Share Bus Pup** in Settings: sends family and friends the download link and simple install steps through WhatsApp, Telegram, SMS or any app.

## 1.12.0
- Puppy theme: a smooth, animated puppy on a little road at the top of the Stops tab. It strolls when there's time, runs when it's nearly time to leave, and sits and wags if the bus is missed. Tap it to make it jump and woof.
- Puppy theme: the full Stop widget has a puppy road. The puppy moves closer to the bus stop sign as leave time approaches and reaches it when it's time to go.

## 1.11.0
- New name: **Bus Pup**.
- New app icon: an orange bus with the cartoon puppy in the windscreen.

## 1.10.0
- Removed the running puppy from the widgets. The Puppy theme keeps its cartoon puppy and paw prints.

## 1.9.1
- Fix: the full Stop widget and the All stops widget showed "Can't load widget" since 1.8.0.
- Build now checks widget layouts so this can't happen again.

## 1.9.0
- Stop widget **Auto** mode: shows the stop whose alert window is open now, or the one that opens next, so one widget covers all your stops. Marked with ⟳.
- Auto is the first choice when placing a Stop widget, and widgets set up before stop picking now use it too.

## 1.8.0
- In the Puppy theme, a cartoon puppy now runs back and forth along the bottom of the Stop widget (3×2 and bigger) and the All stops widget.
- The puppy runs faster in the last 3 minutes before it's time to leave.

## 1.7.0
- New **Puppy** theme 🐶: soft cream colours, an original cartoon puppy and paw prints in the app and on every widget. Pick it in Settings → Theme.
- Alerts say "🐶 Woof!" in the Puppy theme.
- **Classic** dark LED theme stays the default.

## 1.6.0
- Main screen reorganised into three tabs: **Stops** (your live stops), **Widgets** (how to add and use them) and **Settings** (alert permissions, battery, updates, about).
- Slim bars on Stops point to Settings when an update is ready or alerts need a permission; Settings shows a dot too.
- Back from Widgets or Settings returns to Stops.

## 1.5.0
- Stop widget can shrink to **1×1**: bus number with a big leave time.
- Redesigned **3×1** Stop widget: big leave time, bus and stop on their own lines, and the alert bell, so nothing gets cut off.
- Leave times shrink to fit on small widgets instead of being clipped.

## 1.4.0
- New **All stops** widget: every saved stop with when to leave and its next buses.
- The single-stop widget is now called **Stop** and has a 🔔 button to switch that stop's leave alert on or off in one tap.

## 1.3.1
- Full-size widget (3×2 and bigger) lists every bus you track at the stop, scrollable, instead of stopping at four.
- Full view now kicks in on launchers that report a 3×2 widget as shorter.
- Leave line sits right under the stop name so it's always visible.

## 1.3.0
- In-app updates: the app checks GitHub for new versions (on open and daily) and installs them in two taps.
- Permanent signing key, so new versions install over old ones.
- Semantic version numbers shown in the app and on releases.

## 1.2.0
- One widget per stop: placing a widget asks which stop to show.
- Compact 2×1 and 3×1 widget layouts that switch as you resize.

## 1.1.0
- Track several bus numbers at one stop; alerts target the first bus you can catch.

## 1.0.0
- Live LTA arrivals, leave-home alerts, home-screen widget.
