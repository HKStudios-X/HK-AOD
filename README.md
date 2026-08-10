<div align="center">

# HK AOD

**Always-On Display with gesture-based wake for Android**

A lightweight Always-On Display (AOD) app built with Jetpack Compose that shows the clock, notifications, media controls and charging info over the lock screen — and wakes when you lift, shake or wave at your phone.

</div>

---

## ✨ Features

### Always-On Display
- Clean, customizable AOD screen with clock, date and battery info
- Multiple **clock styles**, **fonts** and **sizes**
- 12 / 24 hour format toggle
- Shows **notifications**, **media info**, **charging speed** and more

### Wake Gestures
- **Lift to Wake** — pick up the phone to show the AOD
- **Shake to Wake** — shake the device with adjustable sensitivity
- **Wave to Wake** — wave your hand over the proximity sensor
- **Pocket Detection** — locks the phone when it's in your pocket, wakes it when you take it out

### Media Controls
- Play / pause / skip controls on the AOD
- **Volume slider** with long-press swipe gesture
- Adjustable **volume swipe steps** for one-handed fine-tuning

### Customization
- Per-feature toggles and sensitivity sliders
- Ambient display toggles for notifications, media, charging and call info
- Battery-optimized always-on operation

---

## 📱 How It Works

The app uses an **Accessibility Service** to draw an AOD overlay on top of the lock screen, plus sensors to detect wake gestures:

| Gesture | Sensor | Detection |
|---------|--------|-----------|
| Lift | Accelerometer | Total acceleration magnitude (~9.8 m/s² at rest) deviating from gravity after a confirmed rest period |
| Shake | Accelerometer | Rapid magnitude oscillations (rest→move waves) above a sensitivity threshold |
| Wave | Proximity | Brief sensor cover (30–500 ms) |
| Pocket | Proximity | Sustained sensor cover (≥800 ms) |

The screen timeout turns the display off naturally, and the next `SCREEN_OFF` cycle restarts the AOD.

---

## 🛠 Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Accessibility Service** — AOD overlay rendering
- **Notification Listener Service** — notification data for the AOD
- **Foreground Service** — keeps the app alive for gesture sensing
- **Navigation Compose**, **Lottie**, **Shizuku**, **Material Icons Extended**

---

## 🚀 Getting Started

### Requirements
- Android 10 (API 29) or higher
- A device with an accelerometer and proximity sensor

### Build
```bash
./gradlew assembleRelease
```

### Permissions Required
On first launch, grant the following:

1. **Accessibility Service** — required to show the AOD overlay and lock the screen
2. **Notification Access** — required to show notifications on the AOD
3. **Overlay permission** (draw over other apps)
4. **Ignore battery optimizations** — so gestures keep working while the screen is off
5. **Notifications** — for the foreground service notification

### Setup
1. Install and open the app
2. Grant all required permissions (the onboarding flow guides you)
3. Enable the features you want (Lift, Wave, Shake, Pocket Detection, Ambient Display)
4. Turn off the screen — the AOD will appear
5. Lift, shake or wave to wake it!

---

## 🗂 Project Structure

```
app/src/main/java/com/hktamizhan/hkaod/
├── MainActivity.kt           # Settings UI & onboarding
├── SplashActivity.kt         # App launcher splash
├── AODService.kt             # Accessibility service, sensors, overlay & wake logic
├── AODScreen.kt              # AOD overlay UI (clock, notifications, media controls)
├── AODActivity.kt            # Full-screen AOD activity (alternative view)
└── AODKeepAliveService.kt    # Foreground service to keep sensing alive
```

---

## ⚠️ Notes

- Wake gestures are disabled while a call is in progress
- AOD overlays require an unlocked bootloader-free device with standard Android permissions
- Battery life depends on which features are enabled — enabling more gestures uses more sensor power

---

## 📄 License

This project is for personal/educational use. See the source files for details.

---

Made with ❤️ by **HK Tamizhan**
