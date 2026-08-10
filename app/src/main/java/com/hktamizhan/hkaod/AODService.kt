package com.hktamizhan.hkaod

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

        // Call state for future AODCallScreen
        val callState = MutableStateFlow(android.telephony.TelephonyManager.CALL_STATE_IDLE)
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

    // ─── Overlay ─────────────────────────────────────────────────
    private var overlayView:           android.view.View? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null

    // ─── Notification Repository ──────────────────────────────────
    data class AODNotification(
        val id:          Int,
        val title:       String,
        val body:        String,
        val packageName: String,
        val postTime:    Long = System.currentTimeMillis()
    )

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
                    resetSensorState()
                    checkAndStartAODLogic()
                }

                Intent.ACTION_SCREEN_ON -> {
                    // Display woke (gesture or power button) — the AOD overlay
                    // stays visible. Keep sensors running so the next gesture
                    // and sleep cycle are handled.
                    Log.d("HKAOD", "── SCREEN ON — AOD overlay kept visible, sensors ready ──")
                    val prefs = getSharedPreferences("HKAOD_PREFS", MODE_PRIVATE)
                    if (anySensorEnabled(prefs)) {
                        resetSensorState()
                        registerSensors()
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
    }

    private fun resetSensorState() {
        nearStartTime      = 0L
        isCovered          = false
        liftRestStart      = 0L
        liftMoveStart      = 0L
        shakeWaveCount     = 0
        lastShakeWave      = 0L
        wasMoving          = false
        sensorsRegistered  = false
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
            wl.acquire(2000L) // hold for 2s — enough to bring the display up
            Handler(Looper.getMainLooper()).postDelayed({ wl.release() }, 2000L)
            Log.d("HKAOD", "Display woken via WakeLock — AOD overlay kept visible")
        } catch (e: Exception) {
            Log.e("HKAOD", "WakeLock failed: ${e.message}")
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance             = this
        lastLiftTriggerTime  = 0L
        lastWaveTriggerTime  = 0L
        lastShakeTriggerTime = 0L
        resetSensorState()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximity     = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE)
                as android.telephony.TelephonyManager

        phoneStateListener = object : android.telephony.PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                callState.value = state
                when (state) {
                    android.telephony.TelephonyManager.CALL_STATE_RINGING,
                    android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> {
                        Log.d("HKAOD", "Call state=$state — removing AOD")
                        removeAODOverlay()
                        stopSensors()
                        // TODO: startAODCallOverlay() when AODCallScreen is ready
                    }
                    android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                        Log.d("HKAOD", "Call ended")
                        // TODO: removeAODCallOverlay() when AODCallScreen is ready
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
    // 1. Always start AOD overlay immediately
    // 2. Register sensors if any wake trigger is enabled
    //    → sensors call wakeScreen() which removes the overlay and wakes
    //       the display. The system timeout turns the screen off, and
    //       the next SCREEN_OFF cycle restarts the overlay.
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

        // ✅ Start AOD overlay regardless of which trigger is set
        startAODOverlay()

        // ✅ Register sensors to wake screen (not to start overlay)
        if (isLiftEnabled || isWaveEnabled || isShakeEnabled || isPocketEnabled) {
            resetSensorState()
            registerSensors()
        }
    }

    // ─── Sensors ─────────────────────────────────────────────────
    private fun registerSensors() {
        if (sensorsRegistered) return
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
                // Track how long the sensor has been covered
                if (nearStartTime == 0L) nearStartTime = now

                // Pocket = sustained cover (≥800ms), not a brief hand pass.
                // Only lock once per cover — guarded by isCovered.
                if (pocketDetection && !isCovered && (now - nearStartTime) >= 800L) {
                    isCovered = true
                    lastPocketStateChangeTime = now
                    Log.d("HKAOD", "Pocket detected (${now - nearStartTime}ms) — removing AOD + locking")
                    removeAODOverlay()
                    lockDevice()
                }
            } else {
                val nearDuration = if (nearStartTime > 0L) now - nearStartTime else 0L
                nearStartTime = 0L

                if (nearDuration > 0L) {
                    // ── Wave to wake ──
                    // Brief cover (30-500ms) = a wave. Pocket detection
                    // requires ≥800ms, so waves never lock the phone.
                    if (waveEnabled && nearDuration in 30L..500L
                        && (now - lastWaveTriggerTime) > 1500L) {
                        lastWaveTriggerTime = now
                        Log.d("HKAOD", "Wave → waking screen (near=${nearDuration}ms)")
                        wakeScreen()
                    }

                    // ── Pocket released ──
                    if (pocketDetection && isCovered
                        && (now - lastPocketStateChangeTime) > 1000L) {
                        isCovered = false
                        lastPocketStateChangeTime = now
                        Log.d("HKAOD", "Pocket released — waking screen + AOD")
                        wakeScreen()
                        startAODOverlay()
                    }
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

            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setContent {
                    AODOverlayScreen(
                        isAlwaysOn = true, // ✅ always on — screen timeout handles dismiss
                        onDismiss  = { removeAODOverlay() }
                    )
                }
            }

            overlayView = composeView

            try {
                wm.addView(composeView, params)
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
            val extras = sbn.notification.extras
            val title  = extras.getString("android.title") ?: return
            val body   = extras.getCharSequence("android.text")?.toString() ?: ""
            val current = NotificationRepository.notifications.value.toMutableList()
            current.removeAll { it.id == sbn.id }
            current.add(0, AODNotification(sbn.id, title, body, sbn.packageName))
            NotificationRepository.update(current.take(8))
        }

        override fun onNotificationRemoved(sbn: StatusBarNotification) {
            NotificationRepository.update(
                NotificationRepository.notifications.value.filter { it.id != sbn.id }
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