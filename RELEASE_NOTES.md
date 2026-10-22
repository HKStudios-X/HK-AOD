# Release Notes

## v1.0.0 — Initial Release

**First public release of HK AOD — a gesture-based Always-On Display for Android.**

### ✨ New Features

#### Always-On Display
- AOD overlay with clock, date, battery level and charging info
- Multiple clock styles, custom fonts and adjustable clock size
- 12/24 hour time format toggle
- Ambient toggles for notifications, media info, media controls, charging speed, notification intel and call intel

#### Wake Gestures
- **Lift to Wake** — detects a pick-up via accelerometer magnitude deviation after a confirmed rest period
- **Shake to Wake** — rapid oscillation detection with 5-level adjustable sensitivity
- **Wave to Wake** — wave or pass your hand over the proximity sensor
- **Pocket Detection** — locks the device after a sustained cover (≥800 ms), wakes when removed

#### Media Controls
- Play / pause / previous / next controls on the AOD
- Volume slider with long-press-and-drag swipe gesture
- Adjustable **Volume Swipe Steps** (1–5) for one-handed precision

#### Onboarding & Permissions
- Guided permission flow (accessibility service, notification access, overlay, battery optimization, notifications)
- Foreground keep-alive service for reliable sensing while the screen is off

### 🔧 Improvements
- Magnitude-based gesture detection that is orientation-independent (works in any device orientation)
- Debounced pocket detection to prevent false locks from brief hand passes
- Wave and pocket detection coexist without conflicting
- Wake gestures keep the AOD overlay visible, showing the clock and notifications

### 📦 Technical Details
- **Min SDK:** Android 10 (API 29)
- **Target SDK:** Android 15 (API 35)
- **Stack:** Kotlin, Jetpack Compose (Material 3), Navigation Compose, Lottie, Shizuku
- **Architecture:** Accessibility Service + Notification Listener + Foreground Service

### ⚠️ Known Limitations
- Gesture sensing relies on the device's accelerometer and proximity sensor
- Enable only the gestures you use to maximize battery life
