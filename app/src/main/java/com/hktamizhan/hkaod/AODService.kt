package com.hktamizhan.hkaod

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.sqrt

class AODService : AccessibilityService(), SensorEventListener {

    // ─── Companion ───────────────────────────────────────────────
    companion object {
        private var instance: AODService? = null
        fun getService(): AODService? = instance

        // Standard gravity in m/s² — the accelerometer magnitude at rest
        const val GRAVITY = 9.8f

        // How long the proximity sensor must stay covered to count as a pocket.
        const val POCKET_CONFIRM_MS = 800L

        // Call state for future AODCallScreen
        val callState = MutableStateFlow(android.telephony.TelephonyManager.CALL_STATE_IDLE)

        // Timestamp of the last call that rang but was never answered.
        // Used by "Call Intel" on the AOD to show a "Missed call" line.
        val missedCallAt = MutableStateFlow(0L)

        // Tap-to-Show: minimum accelerometer impulse (m/s²) for a finger tap
        // on the phone to count. Scaled by the Tap Sensitivity setting.
        const val TAP_SPIKE_BASE = 1.15f

        // Tap-to-Show: how long to wait before treating a spike as a single
        // tap. The system's own double-tap wake turns the display on inside
        // this window, and the AOD is then skipped so the lock screen stays.
        const val TAP_CONFIRM_MS = 380L
    }

    // ─── Sensors ─────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelerometer:    Sensor? = null
    private var proximity:        Sensor? = null
    private var sensorsRegistered = false

    private var lastLiftTriggerTime  = 0L
    private var lastWaveTriggerTime  = 0L
    private var lastShakeTriggerTime = 0L
    private var nearStartTime        = 0L   // when proximity first read "near"
    private var isCovered            = false  // pocket confirmed (≥800ms cover)
    private var lastPocketStateChangeTime = 0L

    // Proximity sensors only report a state *change* — there is no second
    // event while the sensor stays covered. The "covered ≥800 ms" pocket
    // confirmation therefore cannot be decided inside onSensorChanged; it is
    // armed as a delayed callback on the "near" event instead.
    private val pocketHandler = Handler(Looper.getMainLooper())
    private var pocketConfirmRunnable: Runnable? = null

    // ─── Lift-to-wake state ───────────────────────────────────────
    // Magnitude-based: total acceleration ≈ 9.8 (gravity) at rest in
    // ANY orientation. A lift = the phone rested long enough, then the
    // magnitude deviated from gravity and stayed deviated (sustained).
    private var liftRestStart     = 0L   // when the phone began resting
    private var liftMoveStart     = 0L   // when sustained movement began

    // ─── Shake-to-wake state ──────────────────────────────────────
    // Counts rest→movement transitions (oscillation waves). A shake
    // alternates moving/resting rapidly; ≥3 waves in a burst = shake.
    private var shakeWaveCount   = 0
    private var lastShakeWave    = 0L
    private var wasMoving        = false

    // ─── Wake Lock (for screen wake) ─────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ─── Telephony ───────────────────────────────────────────────
    private var telephonyManager:   android.telephony.TelephonyManager? = null
    private var phoneStateListener: android.telephony.PhoneStateListener? = null
    private var wasRinging = false

    // ─── Overlay ─────────────────────────────────────────────────
    private var overlayView:           android.view.View? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    // ─── Tap-to-Show state ────────────────────────────────────────
    // A switched-off screen cannot report touches to an app, so a tap is
    // recognised from the small shock it sends through the phone: with the
    // display off and the phone at rest, a sharp, short spike in the
    // accelerometer magnitude counts as a tap. A single tap (no second spike
    // and no wake inside the confirm window) shows the AOD; the system's own
    // double-tap wake is left untouched and keeps going to the lock screen.
    private val tapHandler = Handler(Looper.getMainLooper())
    private var tapConfirmRunnable: Runnable? = null
    private var tapBaseline         = GRAVITY  // low-pass filtered magnitude
    private var tapQuietSince       = 0L       // when the phone last settled
    private var lastTapSpikeAt      = 0L       // last accepted spike time
    private var displayInteractive  = true     // kept in sync with screen broadcasts

    // ─── Notification Repository ──────────────────────────────────
    data class AODNotification(
        val id:          Int,
        val title:       String,
        val body:        String,
        val packageName: String,
        val postTime:    Long = System.currentTimeMillis()
    ) {
        /** Globally unique key — `id` alone collides across apps. */
        val key: String get() = "$packageName/$id"
    }

    object NotificationRepository {
        private val _notifications = MutableStateFlow<List<AODNotification>>(emptyList())
        val notifications: StateFlow<List<AODNotification>> = _notifications
        fun update(list: List<AODNotification>) { _notifications.value = list }
    }

    // ─── Screen State Receiver ────────────────────────────────────
    // SCREEN_OFF → start AOD overlay + register sensors (if any trigger enabled)
    // SCREEN_ON  → sensors keep running for next gesture cycle
    // USER_PRESENT (unlocked) → remove overlay, stop sensors
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("HKAOD", "── SCREEN OFF ──")
                    displayInteractive = false
                    resetSensorState()
                    checkAndStartAODLogic()
                }

                Intent.ACTION_SCREEN_ON -> {
                    // Display woke (double-tap, power button or a gesture).
                    // Always-On mode keeps its overlay alive. "Tap to Show"
                    // deliberately does NOT show the AOD here: a normal wake
                    // (double tap / power) must go to the lock screen — the
                    // AOD is instead shown by the tap detector on a single tap.
                    Log.d("HKAOD", "── SCREEN ON — sensors ready ──")
                    displayInteractive = true
                    val prefs = getSharedPreferences("HKAOD_PREFS", MODE_PRIVATE)
                    if (prefs.getBoolean("ambient_enabled", false)) {
                        startAODOverlay()
                    }
                    if (anySensorEnabled(prefs)) {
                        resetSensorState()
                        registerSensors()
                    } else {
                        stopSensors()
                    }
                }

                Intent.ACTION_USER_PRESENT -> {
                    // Device fully unlocked — remove AOD
                    Log.d("HKAOD", "── USER PRESENT — removing AOD ──")
                    removeAODOverlay()
                    stopSensors()
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────
    private fun anySensorEnabled(prefs: android.content.SharedPreferences): Boolean {
        return prefs.getBoolean("lift_enabled",  false)
                || prefs.getBoolean("wave_enabled",  false)
                || prefs.getBoolean("shake_enabled", false)
                || prefs.getBoolean("pocket_detection_enabled", false)
                || prefs.getBoolean("ambient_tap_to_show", false)
    }

    private fun resetSensorState() {
        liftRestStart      = 0L
        liftMoveStart      = 0L
        shakeWaveCount     = 0
        lastShakeWave      = 0L
        wasMoving          = false
        // Tap-to-Show — a fresh settle is required after every screen change.
        cancelTapConfirmation()
        tapBaseline    = GRAVITY
        tapQuietSince  = 0L
        lastTapSpikeAt = 0L
    }

    // Full pocket-state reset — only on service (re)connect. The pocket state
    // itself must survive screen transitions: the phone stays covered even
    // while the screen turns on/off, and the release must still wake it.
    private fun resetPocketState() {
        cancelPocketConfirmation()
        nearStartTime             = 0L
        isCovered                 = false
        lastPocketStateChangeTime = 0L
    }

    // ─── Pocket confirmation ─────────────────────────────────────
    // Armed on the "near" event; fires 800 ms later only if the sensor is
    // still covered (nearStartTime untouched) and the pocket is not yet
    // confirmed. Cancelled as soon as the sensor reports "far".
    private fun armPocketConfirmation(token: Long) {
        cancelPocketConfirmation()
        val r = Runnable {
            pocketConfirmRunnable = null
            if (nearStartTime == token && token > 0L && !isCovered) {
                isCovered = true
                lastPocketStateChangeTime = System.currentTimeMillis()
                Log.d("HKAOD", "Pocket confirmed (${POCKET_CONFIRM_MS}ms cover) — removing AOD + locking")
                removeAODOverlay()
                lockDevice()
            }
        }
        pocketConfirmRunnable = r
        pocketHandler.postDelayed(r, POCKET_CONFIRM_MS)
    }

    private fun cancelPocketConfirmation() {
        pocketConfirmRunnable?.let { pocketHandler.removeCallbacks(it) }
        pocketConfirmRunnable = null
    }

    // ─── Tap-to-Show detection ────────────────────────────────────
    // Runs on accelerometer samples while the display is off. The phone must
    // have settled first (tapQuietSince), then a finger tap must produce a
    // sharp impulse above the sensitivity threshold. Confirmation is delayed a
    // few hundred ms: if the display comes on inside that window — that is the
    // system's own double-tap wake — the AOD is NOT shown, so double taps keep
    // going to the normal lock screen.
    private fun handleTapDetection(magnitude: Float, now: Long) {
        // Low-pass baseline ≈ the phone's current rest level. Taps are brief,
        // so they barely move it and stand out as an impulse.
        tapBaseline += (magnitude - tapBaseline) * 0.12f
        val impulse = abs(magnitude - tapBaseline)

        if (impulse < 0.22f) {
            if (tapQuietSince == 0L) tapQuietSince = now
            return
        }

        val quietFor = if (tapQuietSince != 0L) now - tapQuietSince else 0L
        tapQuietSince = 0L

        val prefs       = getSharedPreferences("HKAOD_PREFS", MODE_PRIVATE)
        val sensitivity = prefs.getInt("tap_sensitivity", 100).coerceIn(50, 200) / 100f
        val threshold   = TAP_SPIKE_BASE * (1f / sensitivity)

        if (impulse > threshold && quietFor > 200L && (now - lastTapSpikeAt) > 120L) {
            lastTapSpikeAt = now
            Log.d("HKAOD", "Tap spike (impulse=%.2f threshold=%.2f) — confirming…".format(impulse, threshold))
            cancelTapConfirmation()
            val r = Runnable {
                tapConfirmRunnable = null
                when {
                    displayInteractive -> Log.d("HKAOD", "Tap confirm: display already on — system wake keeps the lock screen")
                    isCovered          -> Log.d("HKAOD", "Tap confirm: pocketed — ignored")
                    else -> {
                        Log.d("HKAOD", "Single tap → showing AOD")
                        wakeScreen()
                    }
                }
            }
            tapConfirmRunnable = r
            tapHandler.postDelayed(r, TAP_CONFIRM_MS)
        }
    }

    private fun cancelTapConfirmation() {
        tapConfirmRunnable?.let { tapHandler.removeCallbacks(it) }
        tapConfirmRunnable = null
    }

    // ─── Wake Screen ─────────────────────────────────────────────
    // Sensors call this when they detect a gesture.
    // Wakes the display so the AOD overlay becomes clearly visible.
    // The overlay is NOT removed here — it stays so the user sees the
    // AOD (clock, notifications). Double-tap on the AOD dismisses the
    // overlay to reveal the lock screen; the screen timeout turns the
    // display off and the next SCREEN_OFF cycle restarts the AOD.
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "hkaod:sensorwake"
            )
            // Acquire with an overall safety timeout, and release the moment the
            // display has been brought up. `isHeld` matters: release() on a
            // wake lock that the timeout already released throws
            // "WakeLock under-locked" and would crash the service.
            wl.acquire(5_000L)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (wl.isHeld) wl.release()
                } catch (e: Exception) {
                    Log.w("HKAOD", "WakeLock release failed: ${e.message}")
                }
            }, 2_000L)
            Log.d("HKAOD", "Display woken via WakeLock — AOD overlay kept visible")
        } catch (e: Exception) {
            Log.e("HKAOD", "WakeLock failed: ${e.message}")
        }
        // A gesture must always reveal the AOD, even if the overlay timed out
        // earlier. startAODOverlay() no-ops when the overlay is already up.
        startAODOverlay()
    }

    // ─── Lifecycle ────────────────────────────────────────────────
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance             = this
        lastLiftTriggerTime  = 0L
        lastWaveTriggerTime  = 0L
        lastShakeTriggerTime = 0L
        resetSensorState()
        resetPocketState()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximity     = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        Log.d("HKAOD", "Sensors available: accelerometer=${accelerometer != null} proximity=${proximity != null}")

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE)
                as android.telephony.TelephonyManager

        phoneStateListener = object : android.telephony.PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                callState.value = state
                when (state) {
                    android.telephony.TelephonyManager.CALL_STATE_RINGING -> {
                        wasRinging = true
                        Log.d("HKAOD", "Call state=RINGING — removing AOD")
                        removeAODOverlay()
                        stopSensors()
                    }
                    android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> {
                        wasRinging = false
                        Log.d("HKAOD", "Call state=OFFHOOK — removing AOD")
                        removeAODOverlay()
                        stopSensors()
                    }
                    android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                        // RINGING → IDLE (without OFFHOOK) means the call was
                        // never answered. Remember it so the AOD can show it.
                        if (wasRinging) missedCallAt.value = System.currentTimeMillis()
                        wasRinging = false
                        Log.d("HKAOD", "Call ended")
                    }
                }
            }
        }

        // Registering for call state needs READ_PHONE_STATE. If the user hasn't
        // granted it yet, listen() throws SecurityException — guard so the
        // accessibility service doesn't crash on connect.
        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(
                phoneStateListener,
                android.telephony.PhoneStateListener.LISTEN_CALL_STATE
            )
        } catch (e: SecurityException) {
            Log.w("HKAOD", "Cannot listen to call state — READ_PHONE_STATE not granted")
        }

        // ✅ Register SCREEN_ON, SCREEN_OFF, and USER_PRESENT
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenStateReceiver, filter)
        displayInteractive = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        } catch (_: Exception) { true }

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes   = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags        = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        AODKeepAliveService.start(this)
        Log.d("HKAOD", "Accessibility Service Ready — KeepAlive started")
    }

    override fun onDestroy() {
        super.onDestroy()
        removeAODOverlay()
        @Suppress("DEPRECATION")
        telephonyManager?.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_NONE)
        AODKeepAliveService.stop(this)
        stopSensors()
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun lockDevice() {
        Log.d("HKAOD", "lockDevice called")
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    private fun isInCall(): Boolean {
        return try {
            telephonyManager?.callState != android.telephony.TelephonyManager.CALL_STATE_IDLE
        } catch (_: Exception) { false }
    }

    // ─── AOD Logic ────────────────────────────────────────────────
    // On screen off:
    // 1. Always-On mode → keep the AOD overlay alive for the whole
    //    screen-off period. Otherwise no overlay is kept (a wake
    //    gesture creates it on demand — see wakeScreen()).
    // 2. Register sensors if any wake trigger is enabled
    //    → sensors call wakeScreen() which wakes the display and makes
    //       sure the AOD overlay is showing.
    private fun checkAndStartAODLogic() {
        if (isInCall()) {
            Log.d("HKAOD", "Call in progress — skipping AOD")
            return
        }

        val prefs          = getSharedPreferences("HKAOD_PREFS", MODE_PRIVATE)
        val isAlwaysOn     = prefs.getBoolean("ambient_enabled", false)
        val isLiftEnabled  = prefs.getBoolean("lift_enabled",    false)
        val isWaveEnabled  = prefs.getBoolean("wave_enabled",    false)
        val isShakeEnabled = prefs.getBoolean("shake_enabled",   false)
        val isPocketEnabled = prefs.getBoolean("pocket_detection_enabled", false)

        Log.d("HKAOD", "checkAOD → alwaysOn=$isAlwaysOn lift=$isLiftEnabled wave=$isWaveEnabled shake=$isShakeEnabled pocket=$isPocketEnabled")

        if (isAlwaysOn) {
            // Always-On mode: keep the overlay alive for the whole screen-off
            // period so it is already visible whenever the display wakes.
            startAODOverlay()
        } else {
            // Not always-on: don't leave a Compose overlay alive all night.
            // A wake gesture — or a single tap (Tap to Show) — re-creates it
            // (see wakeScreen()).
            removeAODOverlay()
        }

        // ✅ Register sensors to wake screen (not to start overlay)
        if (isLiftEnabled || isWaveEnabled || isShakeEnabled || isPocketEnabled) {
            resetSensorState()
            registerSensors()
        } else {
            // No triggers enabled — don't keep draining the battery.
            resetSensorState()
            stopSensors()
        }
    }

    // ─── Sensors ─────────────────────────────────────────────────
    private fun registerSensors() {
        if (sensorsRegistered) return
        // Clear any stale registration before (re)registering so the same
        // listener can never be registered twice.
        sensorManager.unregisterListener(this)
        // Accelerometer needs a fast rate (~20ms) — at SENSOR_DELAY_NORMAL (~200ms)
        // the brief peak of a shake/lift falls between samples and is missed.
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // Proximity needs UI rate (~60ms) — waves are brief covers
        // (30-500ms) that SENSOR_DELAY_NORMAL (~200ms) would miss.
        proximity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorsRegistered = true
        Log.d("HKAOD", "Sensors registered")
    }

    private fun stopSensors() {
        // No more proximity events will arrive — drop any in-flight pocket
        // measurement so a stale near event can't confirm a "pocket" later.
        cancelPocketConfirmation()
        nearStartTime = 0L
        // A pending tap confirmation must not wake the screen after the
        // sensors were stopped (incoming call, unlock, service teardown).
        cancelTapConfirmation()
        sensorManager.unregisterListener(this)
        sensorsRegistered = false
        Log.d("HKAOD", "Sensors stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!sensorsRegistered) return

        val prefs = getSharedPreferences("HKAOD_PREFS", MODE_PRIVATE)
        val now   = System.currentTimeMillis()

        // ══ PROXIMITY — pocket detection + wave to wake ══
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val pocketDetection = prefs.getBoolean("pocket_detection_enabled", false)
            val waveEnabled     = prefs.getBoolean("wave_enabled", false)

            if (!pocketDetection && !waveEnabled) return

            val maxRange      = proximity?.maximumRange ?: 5f
            val nearThreshold = if (maxRange > 0f) maxRange * 0.7f else 1f
            val isNear        = event.values[0] < nearThreshold

            if (isNear) {
                // The sensor only reports state *changes* — this single "near"
                // event is all we get until it is uncovered. Start (or resume)
                // tracking and arm the delayed pocket confirmation.
                if (nearStartTime == 0L) {
                    nearStartTime = now
                    if (pocketDetection && !isCovered) armPocketConfirmation(now)
                }
            } else {
                // Cover ended. Cancel a pending pocket confirmation and
                // measure how long the sensor stayed covered.
                cancelPocketConfirmation()
                val hadNear      = nearStartTime > 0L
                val nearDuration = if (hadNear) now - nearStartTime else 0L
                nearStartTime = 0L

                // ── Pocket released ──
                // isCovered is true only after the ≥800ms confirmation, so a
                // wave/tap never triggers this branch.
                if (pocketDetection && isCovered) {
                    isCovered = false
                    if (hadNear && (now - lastPocketStateChangeTime) > 1000L) {
                        lastPocketStateChangeTime = now
                        Log.d("HKAOD", "Pocket released — waking screen + AOD")
                        wakeScreen()
                        startAODOverlay()
                    }
                } else if (isCovered) {
                    // Pocket detection is off now — just drop the stale state.
                    isCovered = false
                }

                // ── Wave to wake ──
                // Brief cover (30-500ms) = a wave. Pocket detection
                // requires ≥800ms, so waves never lock the phone.
                if (waveEnabled && hadNear && nearDuration in 30L..500L
                    && (now - lastWaveTriggerTime) > 1500L) {
                    lastWaveTriggerTime = now
                    Log.d("HKAOD", "Wave → waking screen (near=${nearDuration}ms)")
                    wakeScreen()
                }
            }
        }

        // ══ ACCELEROMETER — lift + shake to wake screen ══
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Total acceleration magnitude. At rest this is exactly ~9.8
            // (gravity) in ANY orientation, so it needs no per-axis baseline.
            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

            // ── Tap to Show ──
            // Only meaningful while the display is really off, and skipped in
            // a confirmed pocket (nothing to see in there).
            if (prefs.getBoolean("ambient_tap_to_show", false)
                && !displayInteractive && !isCovered) {
                handleTapDetection(magnitude, now)
            }

            // ── Lift to Wake Screen ──
            // Phone must rest (magnitude ≈ gravity) for ≥600ms, then the
            // magnitude must deviate from gravity and STAY deviated for
            // ≥250ms (a sustained pick-up, not a bump).
            if (prefs.getBoolean("lift_enabled", false)) {
                val liftMoving = abs(magnitude - GRAVITY) > 1.6f

                if (!liftMoving) {
                    if (liftRestStart == 0L) liftRestStart = now
                    liftMoveStart = 0L
                } else if (liftRestStart > 0L && (now - liftRestStart) >= 600L) {
                    if (liftMoveStart == 0L) liftMoveStart = now
                    if ((now - liftMoveStart) >= 250L
                        && (now - lastLiftTriggerTime) > 2000L) {
                        lastLiftTriggerTime = now
                        liftRestStart       = 0L
                        liftMoveStart       = 0L
                        Log.d("HKAOD", "Lift → wake (mag=$magnitude)")
                        if (!isCovered) wakeScreen()
                    }
                } else {
                    // Movement before a proper rest period — start over
                    liftRestStart = 0L
                    liftMoveStart = 0L
                }
            }

            // ── Shake to Wake Screen ──
            // A shake alternates moving/resting rapidly (magnitude crosses
            // the gravity baseline at each reversal). Count each rest→move
            // transition as one wave; ≥3 waves inside a burst = shake.
            // Sensitivity maps the moving threshold: sens1→2.5, sens5→1.3.
            if (prefs.getBoolean("shake_enabled", false)) {
                val shakeSens = prefs.getFloat("shake_enabled_sens", 3f).coerceIn(1f, 5f)
                val shakeMoving = abs(magnitude - GRAVITY) > (2.8f - shakeSens * 0.3f)

                if (shakeMoving && !wasMoving) {
                    // New wave — restart the burst counter if it went stale
                    if (shakeWaveCount == 0 || (now - lastShakeWave) > 900L) {
                        shakeWaveCount = 1
                    } else {
                        shakeWaveCount++
                    }
                    lastShakeWave = now

                    if (shakeWaveCount >= 3
                        && (now - lastShakeTriggerTime) > 1500L) {
                        lastShakeTriggerTime = now
                        shakeWaveCount       = 0
                        Log.d("HKAOD", "Shake → wake (waves=3, sens=$shakeSens)")
                        if (!isCovered) wakeScreen()
                    }
                }
                wasMoving = shakeMoving
            }
        }
    }

    // ─── Overlay Start ───────────────────────────────────────────
    fun startAODOverlay() {
        Handler(Looper.getMainLooper()).post {
            if (overlayView != null) {
                Log.d("HKAOD", "Overlay already showing — skip")
                return@post
            }

            Log.d("HKAOD", "startAODOverlay — building overlay")

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // ✅ Always use ACCESSIBILITY_OVERLAY — works on locked AND unlocked screen
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE          or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED       or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN       or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS       or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity          = Gravity.TOP or Gravity.START

                buttonBrightness = 0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            val lifecycleOwner = OverlayLifecycleOwner().also {
                it.onCreate()
                it.onStart()
                it.onResume()
            }
            overlayLifecycleOwner = lifecycleOwner

            // Persistent only when the dashboard's "Always On" mode is on.
            // Otherwise the overlay times out so it doesn't stay stuck.
            // In "Tap to Show" mode the timeout only dismisses the AOD — it
            // must NOT lock the device, because the user woke it to glance at
            // the AOD and may be about to unlock.
            val prefs = getSharedPreferences("HKAOD_PREFS", MODE_PRIVATE)
            val isAlwaysOn = prefs.getBoolean("ambient_enabled", false)
            val lockOnTimeout = !prefs.getBoolean("ambient_tap_to_show", false)

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    AODOverlayScreen(
                        isAlwaysOn    = isAlwaysOn,
                        lockOnTimeout = lockOnTimeout,
                        onDismiss     = { removeAODOverlay() }
                    )
                }
            }

            overlayView = composeView

            try {
                wm.addView(composeView, params)
                // Hide the status/navigation bars for this overlay window so the
                // AOD really is a full-black screen (API 30+).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    composeView.windowInsetsController?.hide(android.view.WindowInsets.Type.systemBars())
                }
                Log.d("HKAOD", "AOD Overlay addView SUCCESS")
            } catch (e: Exception) {
                Log.e("HKAOD", "addView FAILED: ${e.message}")
                overlayView           = null
                overlayLifecycleOwner = null
            }
        }
    }

    // ─── Overlay Remove ──────────────────────────────────────────
    fun removeAODOverlay() {
        Handler(Looper.getMainLooper()).post {
            val view = overlayView ?: run {
                Log.d("HKAOD", "removeAODOverlay — nothing to remove")
                return@post
            }
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                overlayLifecycleOwner?.onPause()
                overlayLifecycleOwner?.onDestroy()
                wm.removeView(view)
                Log.d("HKAOD", "AOD Overlay removed")
            } catch (e: Exception) {
                Log.e("HKAOD", "removeView FAILED: ${e.message}")
            } finally {
                overlayView           = null
                overlayLifecycleOwner = null
            }
        }
    }

    // ─── Notification Listener ───────────────────────────────────
    class AODNotificationListener : NotificationListenerService() {
        override fun onNotificationPosted(sbn: StatusBarNotification) {
            // Never mirror our own (foreground-service) notification, group
            // summaries or other ongoing/system entries on the AOD.
            if (sbn.packageName == packageName) return
            val n = sbn.notification ?: return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

            val extras = n.extras
            // Titles/bodies are CharSequences (often SpannedString) — calling
            // getString() throws ClassCastException on those.
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            if (title.isNullOrEmpty()) return
            val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                ?: ""

            val notif = AODNotification(sbn.id, title, body, sbn.packageName)
            val current = NotificationRepository.notifications.value.toMutableList()
            current.removeAll { it.key == notif.key }
            current.add(0, notif)
            NotificationRepository.update(current.take(8))
        }

        override fun onNotificationRemoved(sbn: StatusBarNotification) {
            val key = "${sbn.packageName}/${sbn.id}"
            NotificationRepository.update(
                NotificationRepository.notifications.value.filter { it.key != key }
            )
        }
    }
}

// ─── OverlayLifecycleOwner ───────────────────────────────────────
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store             = ViewModelStore()
    private val controller        = SavedStateRegistryController.create(this)

    override val lifecycle:          Lifecycle          get() = lifecycleRegistry
    override val viewModelStore:     ViewModelStore     get() = store
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun onCreate() {
        controller.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }
    fun onStart()   { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)   }
    fun onResume()  { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)  }
    fun onPause()   { lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)   }
    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}