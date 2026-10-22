package com.hktamizhan.hkaod

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.hktamizhan.hkaod.AODService.AODNotification
import com.hktamizhan.hkaod.AODService.NotificationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
@Composable
fun AODScreen(
    onDismiss: () -> Unit = { AODService.getService()?.removeAODOverlay() },
    onInteraction: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("HKAOD_PREFS", Context.MODE_PRIVATE) }
    val fadeAlpha    = remember { Animatable(1f) }
    var startDismiss by remember { mutableStateOf(false) }
    LaunchedEffect(startDismiss) {
        if (startDismiss) {
            fadeAlpha.animateTo(
                targetValue   = 0f,
                animationSpec = tween(400, easing = FastOutSlowInEasing)
            )
            delay(80L)
            onDismiss()
        }
    }
    var showNotifications by remember { mutableStateOf(prefs.getBoolean("ambient_notifications", false)) }
    var showMediaIntel    by remember { mutableStateOf(prefs.getBoolean("ambient_media_intel", false)) }
    var showMediaControls by remember { mutableStateOf(prefs.getBoolean("ambient_media_controls", false)) }
    var showCharging      by remember { mutableStateOf(prefs.getBoolean("ambient_charging_speed", false)) }
    var showNotifIntel    by remember { mutableStateOf(prefs.getBoolean("ambient_notification_intel", false)) }
    var showCallIntel     by remember { mutableStateOf(prefs.getBoolean("ambient_call_intel", false)) }
    var is24Hour          by remember { mutableStateOf(prefs.getBoolean("time_format_24", false)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            showNotifications = prefs.getBoolean("ambient_notifications", false)
            showMediaIntel    = prefs.getBoolean("ambient_media_intel", false)
            showMediaControls = prefs.getBoolean("ambient_media_controls", false)
            showCharging      = prefs.getBoolean("ambient_charging_speed", false)
            showNotifIntel    = prefs.getBoolean("ambient_notification_intel", false)
            showCallIntel     = prefs.getBoolean("ambient_call_intel", false)
            is24Hour          = prefs.getBoolean("time_format_24", false)
        }
    }
    val clockStyle by ClockStyleRepository.style.collectAsState()
    val clockSize by ClockStyleRepository.size.collectAsState()
    val customFont by ClockStyleRepository.customFont.collectAsState()
    val useCustomFont by ClockStyleRepository.useCustomFont.collectAsState()
    val clockFontFamily = if (useCustomFont && customFont != null) customFont!!
                          else ClockStyleRepository.fontFamilyFor(clockStyle)
    LaunchedEffect(Unit) { ClockStyleRepository.loadFromPrefs(context) }
    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }
    LaunchedEffect(is24Hour) {
        while (true) {
            val cal     = Calendar.getInstance()
            val timeFmt = SimpleDateFormat(if (is24Hour) "HH:mm" else "hh:mm", Locale.getDefault())
            val dateFmt = SimpleDateFormat("EEE, dd MMMM", Locale.getDefault())
            currentTime = timeFmt.format(cal.time)
            currentDate = dateFmt.format(cal.time)
            delay(1000L)
        }
    }

    // Element positions (x/y fractions, scale, visibility)
    val clockPos    = AODPositions.read(prefs, "clock")
    val datePos     = AODPositions.read(prefs, "date")
    val notifPos    = AODPositions.read(prefs, "notifications")
    val mediaPos    = AODPositions.read(prefs, "media")
    val chargingPos = AODPositions.read(prefs, "charging")
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isCharging      by remember { mutableStateOf(false) }
    var chargingWatts   by remember { mutableStateOf("") }
    var chargingLabel   by remember { mutableStateOf("") }
    var batteryLevel    by remember { mutableIntStateOf(0) }
    var remainingTime   by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                // Instant level / charging-state update on plug, unplug or % change.
                // The rate (W / mA), charging label and time estimates are computed in
                // the dynamic polling loop below so they refresh every second.
                batteryLevel = if (scale > 0) (level * 100 / scale) else batteryLevel
                isCharging   = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    // Coulometer state for the delta-current fallback on devices that don't
    // expose CURRENT_NOW / CURRENT_AVERAGE.
    var prevCounter by remember { mutableStateOf(0L) }
    var prevCounterTime by remember { mutableStateOf(0L) }
    // Debug: log charging readings only when they change (avoids log spam).
    var lastChargeSig by remember { mutableStateOf("") }

    // Dynamic battery/charging poll — refreshes the live rate (W / mA), the
    // charging label, the charge ETA and the discharge estimate every second.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val bm     = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

                val level   = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale   = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status  = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                var voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0  // mV

                val pct = if (scale > 0) level * 100 / scale else batteryLevel
                batteryLevel = pct
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                isCharging = charging

                // ── Live current (mA) ──
                // CURRENT_NOW's sign convention differs across OEMs (some
                // report positive while charging, others negative) and it can
                // be 0 on devices that don't expose it. Normalise to a
                // positive magnitude, then fall back to CURRENT_AVERAGE and
                // finally to charge-counter deltas.
                var currentMA = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000f
                if (abs(currentMA) < 50f) {
                    val avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) / 1000f
                    if (abs(avg) >= 50f) currentMA = avg
                }

                val now = System.currentTimeMillis()
                val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
                if (abs(currentMA) < 50f && chargeCounter > 0 && prevCounter > 0) {
                    val dt = now - prevCounterTime
                    if (dt in 300L..30_000L) {
                        // µAh over dt ms → µA: ×3.6e6 / dt  (1 µAh = 3.6e6 µA·ms)
                        val estUa = (chargeCounter - prevCounter) * 3_600_000L / dt
                        val estMA = abs(estUa) / 1000f
                        if (estMA in 50f..10_000f) currentMA = estMA
                    }
                }
                prevCounter     = chargeCounter
                prevCounterTime = now

                // Sensor magnitude — meaningful for both charging and discharging.
                val liveMA    = abs(currentMA)
                val saneRate  = liveMA in 50f..10_000f        // ignore nonsense sensor reads
                val showWatts = prefs.getBoolean("charging_show_watts", true)

                // Battery-side voltage. A few devices report no EXTRA_VOLTAGE —
                // fall back to a typical charging voltage so watts still show.
                if (voltage <= 0 && saneRate) voltage = 4000
                val voltageV  = if (voltage > 0) voltage / 1000f else 0f

                // Android has no public API for the system's negotiated charger
                // rate or a system-provided "time until full", so the wattage
                // and ETA are derived from the live current above.
                val wattValue = if (saneRate) liveMA * voltageV / 1000f else 0f

                if (charging) {
                    chargingWatts = when {
                        !saneRate        -> ""
                        showWatts        -> "%.1f W".format(wattValue)
                        else             -> "%.0f mA".format(liveMA)
                    }
                    // Charger-side wattage is not exposed by any public API, and
                    // battery watts are always lower than the charger's rating
                    // (an 18W charger puts ~4A ≈ 14W into the battery). The
                    // current actually flowing into the battery is the most
                    // consistent cross-device signal for the charge speed.
                    chargingLabel = when {
                        plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Charging"
                        liveMA >= 5000f -> "Super Fast Charging"
                        liveMA >= 2500f -> "Fast Charging"
                        liveMA >= 1000f -> "Charging"
                        else            -> "Slow Charging"
                    }
                    // Estimated time until full = remaining capacity ÷ live current.
                    remainingTime = when {
                        pct >= 100    -> "Fully charged"
                        !saneRate || chargeCounter <= 0 || pct !in 1..99 -> ""
                        else -> {
                            val fullUah      = chargeCounter * 100L / pct
                            val remainingUah = (fullUah - chargeCounter).coerceAtLeast(0L)
                            val remainingMah = remainingUah / 1000L
                            if (remainingMah > 20_000L) {
                                ""  // absurd extrapolation → sensor noise
                            } else {
                                val hoursToFull = remainingUah.toFloat() / (liveMA * 1000f)
                                val hh = hoursToFull.toInt()
                                val mm = ((hoursToFull - hh) * 60).toInt()
                                when {
                                    hh > 0 -> "${hh}h ${mm}m until full"
                                    mm > 0 -> "${mm}m until full"
                                    else   -> ""
                                }
                            }
                        }
                    }
                } else {
                    chargingWatts = ""
                    chargingLabel = ""
                    // Discharge estimate (time left on battery).
                    remainingTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        val remainingMillis = powerManager.batteryDischargePrediction?.toMillis()
                        if (remainingMillis != null && remainingMillis > 0) {
                            val hours = (remainingMillis / (1000 * 60 * 60)).toInt()
                            val mins  = ((remainingMillis / (1000 * 60)) % 60).toInt()
                            when {
                                hours > 0 -> "${hours}h ${mins}m left"
                                mins  > 0 -> "${mins}m left"
                                else      -> ""
                            }
                        } else ""
                    } else {
                        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                        if (saneRate && chargeCounter > 0) {
                            val hoursLeft = chargeCounter.toFloat() / (liveMA * 1000f)
                            val hh = hoursLeft.toInt()
                            val mm = ((hoursLeft - hh) * 60).toInt()
                            when {
                                hh > 0 -> "${hh}h ${mm}m left"
                                mm > 0 -> "${mm}m left"
                                else   -> ""
                            }
                        } else ""
                    }
                }

                // Helps diagnosing device-specific charging readings via
                // `adb logcat -s HKAOD` — only logs when something changes.
                val debugSig = "$status|$plugged|$voltage|$currentMA|$chargingLabel"
                if (debugSig != lastChargeSig) {
                    lastChargeSig = debugSig
                    Log.d(
                        "HKAOD",
                        "Charging: status=$status plugged=$plugged voltage=${voltage}mV " +
                                "raw=${currentMA}mA live=${liveMA}mA watts=$wattValue label=$chargingLabel"
                    )
                }
            } catch (_: Exception) {}
            delay(1000L)
        }
    }

    // ── Media session ──
    var trackName  by remember { mutableStateOf("") }
    var albumName  by remember { mutableStateOf("") }
    var isPlaying  by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component   = android.content.ComponentName(
                    context, AODService.AODNotificationListener::class.java
                )
                val controllers = msm.getActiveSessions(component)
                val active      = controllers.firstOrNull()
                val meta        = active?.metadata
                trackName = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                albumName = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: ""
                isPlaying = active?.playbackState?.state == PlaybackState.STATE_PLAYING
            } catch (_: Exception) {
            }
            delay(1500L)
        }
    }

    // ── Notifications & call intel ──
    val notifications by NotificationRepository.notifications.collectAsState()
    val missedCallAt by AODService.missedCallAt.collectAsState()
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val missedCallRecent = showCallIntel && missedCallAt > 0L &&
            (nowTick - missedCallAt) < 15 * 60_000L
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(fadeAlpha.value)
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            // Report any touch so the overlay's auto-dismiss timer can reset.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDown() }) onInteraction()
                    }
                }
            }
            .pointerInput(Unit) {
                // Single tap only — double taps are left to the system.
                detectTapGestures(onTap = { startDismiss = true })
            }
    ) {
        // Clock
        if (clockPos.visible) {
            AODPlacedElement(pos = clockPos, containerPx = containerSize) {
                AODClock(
                    time       = currentTime,
                    fontFamily = clockFontFamily,
                    fontSize   = clockSize,
                    textAlign  = aodTextAlign(clockPos.align)
                )
            }
        }

        // Date
        if (datePos.visible) {
            AODPlacedElement(pos = datePos, containerPx = containerSize) {
                Text(
                    text      = currentDate,
                    color     = Color.White,
                    fontSize  = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = aodTextAlign(datePos.align)
                )
            }
        }

        // Notifications + intel (notification count / missed call)
        val hasNotifs = notifications.isNotEmpty()
        val showNotifBlock = notifPos.visible && (
                (showNotifications && hasNotifs) ||
                (showNotifIntel && hasNotifs) ||
                missedCallRecent
        )
        if (showNotifBlock) {
            AODPlacedElement(pos = notifPos, containerPx = containerSize) {
                Column(
                    modifier = Modifier.widthIn(max = 320.dp),
                    horizontalAlignment = aodAlignment(notifPos.align)
                ) {
                    if (missedCallRecent) {
                        Text(
                            text       = "Missed call",
                            color      = HKAOD_Red,
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign  = aodTextAlign(notifPos.align),
                            modifier   = Modifier.fillMaxWidth()
                        )
                    }
                    if (showNotifIntel && hasNotifs && !showNotifications) {
                        Text(
                            text      = if (notifications.size == 1) "1 notification"
                                        else "${notifications.size} notifications",
                            color     = Color.White.copy(0.75f),
                            fontSize  = 14.sp,
                            textAlign = aodTextAlign(notifPos.align),
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }
                    if (showNotifications && hasNotifs) {
                        if (missedCallRecent) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        AODNotificationList(
                            notifications = notifications,
                            align     = notifPos.align,
                            modifier = Modifier
                                .widthIn(max = 320.dp)
                                .heightIn(max = 200.dp)
                        )
                    }
                }
            }
        }

        // Media Intel + Media Controls — rendered as ONE block so the two
        // never overlap each other at the same position.
        if (mediaPos.visible && trackName.isNotEmpty() && (showMediaIntel || showMediaControls)) {
            AODPlacedElement(pos = mediaPos, containerPx = containerSize) {
                Column(
                    modifier = Modifier.widthIn(max = 320.dp),
                    horizontalAlignment = aodAlignment(mediaPos.align)
                ) {
                    if (showMediaIntel) {
                        AODMediaRow(
                            trackName = trackName,
                            albumName = albumName,
                            align     = mediaPos.align,
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }
                    if (showMediaIntel && showMediaControls) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    if (showMediaControls) {
                        AODMediaControlsRow(
                            isPlaying = isPlaying,
                            trackName = trackName,
                            align     = mediaPos.align,
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Charging / Battery
        if (showCharging && chargingPos.visible) {
            AODPlacedElement(pos = chargingPos, containerPx = containerSize) {
                if (isCharging) {
                    AODChargingRow(
                        level  = batteryLevel,
                        watts  = chargingWatts,
                        label  = chargingLabel,
                        eta    = remainingTime,
                        align  = chargingPos.align,
                        modifier = Modifier.widthIn(max = 320.dp)
                    )
                } else {
                    AODBatteryRow(
                        level = batteryLevel,
                        remainingTime = remainingTime,
                        align = chargingPos.align,
                        modifier = Modifier.widthIn(max = 320.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun aodTextAlign(align: String): TextAlign = when (align) {
    "start" -> TextAlign.Start
    "end"   -> TextAlign.End
    else    -> TextAlign.Center
}

fun aodAlignment(align: String): Alignment.Horizontal = when (align) {
    "start" -> Alignment.Start
    "end"   -> Alignment.End
    else    -> Alignment.CenterHorizontally
}

@Composable
fun BoxScope.AODPlacedElement(
    pos: AODPosition,
    containerPx: IntSize,
    content: @Composable BoxScope.() -> Unit
) {
    // The anchor is the CENTER of the element: x/y fractions position the
    // element's centre, and scaling pivots about the centre so it never drifts.
    var elemSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .wrapContentSize()
            .onSizeChanged { elemSize = it }
            // offset FIRST, graphicsLayer INSIDE: scaling then pivots about the
            // element's own centre, so it stays pinned to x/y at any scale.
            .offset {
                IntOffset(
                    (pos.x * containerPx.width - elemSize.width / 2f).roundToInt(),
                    (pos.y * containerPx.height - elemSize.height / 2f).roundToInt()
                )
            }
            .graphicsLayer {
                val s = pos.scale
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
    ) { content() }
}

@Composable
fun AODClock(
    time:       String,
    fontFamily: FontFamily,
    fontSize:   Float = 80f,
    textAlign:  TextAlign = TextAlign.Center,
    modifier:   Modifier = Modifier
) {
    Text(
        text          = time,
        fontSize      = fontSize.sp,
        fontWeight    = FontWeight.Thin,
        color         = Color.White,
        fontFamily    = fontFamily,
        letterSpacing = 4.sp,
        modifier      = modifier,
        textAlign     = textAlign
    )
}
@Composable
fun AODNotificationList(
    notifications: List<AODNotification>,
    align: String = "center",
    modifier: Modifier = Modifier
) {
    val textAlign = aodTextAlign(align)
    LazyColumn(
        modifier = modifier
            .heightIn(max = 220.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(notifications, key = { it.key }) { notif ->
            AODNotificationItem(notif, textAlign)
        }
    }
}

@Composable
fun AODNotificationItem(notif: AODNotification, textAlign: TextAlign = TextAlign.Center) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(notif.key) {
        delay(80)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec  = tween(400, easing = FastOutSlowInEasing)
        ) + fadeIn(tween(400)),
        exit = slideOutHorizontally(
            targetOffsetX = { -it },
            animationSpec = tween(300)
        ) + fadeOut(tween(300))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text       = notif.title,
                color      = Color.White,
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                textAlign  = textAlign
            )
            if (notif.body.isNotEmpty()) {
                Text(
                    text     = notif.body,
                    color    = Color.White.copy(0.7f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = textAlign
                )
            }
        }
    }
}

@Composable
fun AODMediaRow(
    trackName: String,
    albumName: String,
    align: String = "center",
    modifier: Modifier = Modifier
) {
    val displayText = if (albumName.isNotEmpty()) "$trackName · $albumName" else trackName

    Column(
        modifier = modifier,
        horizontalAlignment = aodAlignment(align)
    ) {
        Text(
            text       = displayText,
            color      = Color.White,
            fontSize   = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.fillMaxWidth(),
            textAlign  = aodTextAlign(align)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(1.dp)
                .background(Color.White.copy(0.4f))
        )
    }
}

@Composable
fun AODChargingRow(
    level: Int,
    watts: String,
    label: String,
    eta: String,
    align: String = "center",
    modifier: Modifier = Modifier
) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.5f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val textAlign = aodTextAlign(align)

    Column(
        modifier = modifier,
        horizontalAlignment = aodAlignment(align)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = when (align) {
                "start" -> Arrangement.Start
                "end"   -> Arrangement.End
                else    -> Arrangement.Center
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter            = painterResource(id = R.drawable.lighting),
                contentDescription = null,
                tint               = Color.White.copy(pulse),
                modifier           = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            // Battery % stays visible while charging.
            Text(
                text       = "$level%",
                color      = Color.White,
                fontSize   = 15.sp,
                fontWeight = FontWeight.Medium
            )
            if (watts.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "·", color = Color.White.copy(0.5f), fontSize = 15.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text       = watts,
                    color      = Color.White,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (label.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text     = label,
                color    = Color.White.copy(0.7f),
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = textAlign
            )
        }
        if (eta.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text     = eta,
                color    = Color.White.copy(0.5f),
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = textAlign
            )
        }
    }
}

@Composable
fun AODBatteryRow(
    level: Int,
    remainingTime: String,
    align: String = "center",
    modifier: Modifier = Modifier
) {
    val textAlign = aodTextAlign(align)
    Column(
        modifier = modifier,
        horizontalAlignment = aodAlignment(align)
    ) {
        Text(
            text       = "$level%",
            color      = Color.White,
            fontSize   = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.fillMaxWidth(),
            textAlign  = textAlign
        )
        if (remainingTime.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text     = remainingTime,
                color    = Color.White.copy(0.7f),
                fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = textAlign
            )
        }
    }

}
@Composable
fun AODMediaControlsRow(
    isPlaying: Boolean,
    trackName: String,
    align: String = "center",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val swipeSteps = remember {
        context.getSharedPreferences("HKAOD_PREFS", Context.MODE_PRIVATE)
            .getInt("volume_swipe_steps", 3)
    }
    // More steps = finer control = more drag needed per level change
    val swipeDivisor = 80f + swipeSteps * 40f

    var currentVol by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var showSlider by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var sliderProgress by remember { mutableFloatStateOf(currentVol.toFloat() / maxVol) }
    val volumeIcon = when {
        currentVol == 0 -> Icons.Default.VolumeOff
        currentVol <= maxVol / 3 -> Icons.Default.VolumeDown
        else -> Icons.Default.VolumeUp
    }

    var transportControls by remember { mutableStateOf<android.media.session.MediaController.TransportControls?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val cn = ComponentName(context, AODService.AODNotificationListener::class.java)
                val ctrl = msm.getActiveSessions(cn).firstOrNull()
                transportControls = ctrl?.transportControls
            } catch (_: Exception) {}
            delay(2000L)
        }
    }

    // Auto-hide the slider 2 s after the gesture ends — never mid-drag.
    LaunchedEffect(showSlider, isDragging) {
        if (showSlider && !isDragging) {
            delay(2000L)
            showSlider = false
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = showSlider,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Slider(
                    value = sliderProgress,
                    onValueChange = { sliderProgress = it },
                    onValueChangeFinished = {
                        val vol = (sliderProgress * maxVol).toInt().coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                        currentVol = vol
                    },
                    valueRange = 0f..1f,
                    steps = maxVol - 1,
                    modifier = Modifier.fillMaxWidth(0.7f).height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(0.3f)
                    )
                )
                if (trackName.isNotEmpty()) {
                    Text(
                        text = trackName,
                        color = Color.White.copy(0.6f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = aodTextAlign(align)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(0.85f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { transportControls?.skipToPrevious() }
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White.copy(0.8f),
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            IconButton(
                onClick = {
                    if (isPlaying) transportControls?.pause()
                    else transportControls?.play()
                }
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            IconButton(
                onClick = { transportControls?.skipToNext() }
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White.copy(0.8f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Volume button — centered below transport controls
        Box(
            modifier = Modifier
                .size(48.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            showSlider = true
                            isDragging = true
                            currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            sliderProgress = currentVol.toFloat() / maxVol
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            sliderProgress = (sliderProgress + dragAmount.x / swipeDivisor)
                                .coerceIn(0f, 1f)
                            val newVol = (sliderProgress * maxVol).toInt().coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            currentVol = newVol
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = {
                            isDragging = false
                            showSlider = false
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = volumeIcon,
                contentDescription = "Volume",
                tint = Color.White.copy(0.8f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
@Composable
fun AODOverlayScreen(
    isAlwaysOn: Boolean,
    lockOnTimeout: Boolean = true,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // The overlay is created when the screen turns off (Always-On mode) or by a
    // wake gesture (non-always-on mode). The auto-dismiss timer must only run
    // while the display is actually ON — otherwise the overlay would lock and
    // remove itself seconds after screen-off, long before a lift / wave / shake
    // gesture ever wakes the screen, so the gesture would reveal the plain lock
    // screen instead of the AOD.
    var displayOn by remember { mutableStateOf(isDisplayOn(context)) }
    var interactionKey by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON  -> displayOn = true
                    Intent.ACTION_SCREEN_OFF -> displayOn = false
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        onDispose { context.unregisterReceiver(receiver) }
    }

    // A gesture wakes the display and creates the overlay almost in the same
    // instant — re-check the display state once shortly after to cover that race.
    LaunchedEffect(Unit) {
        delay(500L)
        displayOn = isDisplayOn(context)
    }

    // Auto-dismiss after the user-configured AOD timeout (Ambient Settings);
    // any touch resets the timer. Non-always-on gesture mode additionally locks
    // the device so the display returns to sleep; "Tap to Show" only dismisses
    // (see lockOnTimeout), which reveals the normal lock screen.
    val timeoutMs = remember {
        (context.getSharedPreferences("HKAOD_PREFS", Context.MODE_PRIVATE)
            .getInt("aod_timeout_seconds", 10) * 1000L).coerceAtLeast(2_000L)
    }
    LaunchedEffect(isAlwaysOn, displayOn, interactionKey) {
        if (!isAlwaysOn && displayOn) {
            delay(timeoutMs)
            if (lockOnTimeout) AODService.getService()?.lockDevice()
            onDismiss()
        }
    }

    AODScreen(onDismiss = onDismiss, onInteraction = { interactionKey++ })
}

private fun isDisplayOn(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isInteractive
    } catch (_: Exception) {
        true
    }
}