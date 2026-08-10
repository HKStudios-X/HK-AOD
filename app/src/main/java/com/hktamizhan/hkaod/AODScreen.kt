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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.sqrt
@Composable
fun AODScreen() {
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
            AODService.getService()?.removeAODOverlay()
        }
    }
    var firstTapOffset  by remember { mutableStateOf(Offset.Zero) }
    var firstTapTime    by remember { mutableLongStateOf(0L) }
    val doubleTapRadius = 60f
    val doubleTapTimeout = 600L
    var showNotifications by remember { mutableStateOf(prefs.getBoolean("ambient_notifications", false)) }
    var showMediaIntel    by remember { mutableStateOf(prefs.getBoolean("ambient_media_intel", false)) }
    var showCharging      by remember { mutableStateOf(prefs.getBoolean("ambient_charging_speed", false)) }
    var showNotifIntel    by remember { mutableStateOf(prefs.getBoolean("ambient_notification_intel", false)) }
    var is24Hour          by remember { mutableStateOf(prefs.getBoolean("time_format_24", false)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            showNotifications = prefs.getBoolean("ambient_notifications", false)
            showMediaIntel    = prefs.getBoolean("ambient_media_intel", false)
            showCharging      = prefs.getBoolean("ambient_charging_speed", false)
            showNotifIntel    = prefs.getBoolean("ambient_notification_intel", false)
            is24Hour          = prefs.getBoolean("time_format_24", false)
        }
    }
    val clockStyle by ClockStyleRepository.style.collectAsState()
    val clockSize by ClockStyleRepository.size.collectAsState()
    val customFont by ClockStyleRepository.customFont.collectAsState()
    val useCustomFont by ClockStyleRepository.useCustomFont.collectAsState()
    val clockFontFamily = if (useCustomFont && customFont != null) customFont!!
                          else ClockStyleRepository.fontFamilyFor(clockStyle)
    // Restore an imported font after a cold start (overlay runs in the service process).
    LaunchedEffect(Unit) { ClockStyleRepository.loadPersistedCustomFont(context) }
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
                val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0  // mV

                val pct = if (scale > 0) level * 100 / scale else batteryLevel
                batteryLevel = pct
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                isCharging = charging

                // Live instantaneous current — read every tick so the rate is dynamic.
                val currentMicroA = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val currentMagA   = abs(currentMicroA)
                val currentA      = currentMagA / 1_000_000f
                val voltageV      = if (voltage > 0) voltage / 1000f else 0f
                val watts         = currentA * voltageV
                val currentMA     = currentMagA / 1000f          // µA → mA
                val showWatts     = prefs.getBoolean("charging_show_watts", true)

                if (charging) {
                    chargingWatts = when {
                        showWatts && watts > 0.1f    -> "%.1f W".format(watts)
                        !showWatts && currentMA > 0f -> "%.0f mA".format(currentMA)
                        else                         -> ""
                    }
                    chargingLabel = when {
                        plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Charging"
                        watts >= 45f -> "Super Fast Charging"
                        watts >= 25f -> "Fast Charging"
                        watts >= 10f -> "Charging"
                        else         -> "Slow Charging"
                    }
                    // Estimated time until full = remaining capacity ÷ live current.
                    remainingTime = if (pct >= 100) {
                        "Fully charged"
                    } else {
                        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) // µAh
                        if (chargeCounter > 0 && currentMagA > 0 && pct in 1..99) {
                            val fullUah      = chargeCounter.toLong() * 100L / pct
                            val remainingUah = (fullUah - chargeCounter).coerceAtLeast(0L)
                            val hoursToFull  = remainingUah.toFloat() / currentMagA.toFloat()
                            val hh = hoursToFull.toInt()
                            val mm = ((hoursToFull - hh) * 60).toInt()
                            when {
                                hh > 0 -> "${hh}h ${mm}m until full"
                                mm > 0 -> "${mm}m until full"
                                else   -> ""
                            }
                        } else ""
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
                        if (currentMicroA < 0 && chargeCounter > 0) {
                            val hoursLeft = chargeCounter.toFloat() / currentMagA.toFloat()
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

    // ── Notifications ──
    val notifications by NotificationRepository.notifications.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(fadeAlpha.value)
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val now  = System.currentTimeMillis()
                        val dist = sqrt(
                            (offset.x - firstTapOffset.x) * (offset.x - firstTapOffset.x) +
                                    (offset.y - firstTapOffset.y) * (offset.y - firstTapOffset.y)
                        )
                        if (now - firstTapTime < doubleTapTimeout && dist < doubleTapRadius) {
                            firstTapTime = 0L
                            startDismiss = true  // ✅ triggers LaunchedEffect
                        } else {
                            firstTapOffset = offset
                            firstTapTime   = now
                        }
                    })
            }
    ){
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {


            Column(modifier = Modifier.padding(top = 80.dp)) {

                AODClock(
                    time      = currentTime,
                    fontFamily = clockFontFamily,
                    fontSize   = clockSize
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text      = currentDate,
                    color     = Color.White,
                    fontSize  = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier  = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(50.dp))
                // Show the notification list whenever the "Notifications" toggle is on.
                // (Previously this also required "Notification Intel", so enabling
                //  "Notifications" alone never displayed anything.)
                if (showNotifications && notifications.isNotEmpty()) {
                    AODNotificationList(notifications = notifications)
                }
            }
            Column(
                modifier = Modifier.padding(bottom = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (showMediaIntel && trackName.isNotEmpty()) {
                    AODMediaRow(trackName = trackName, albumName = albumName)
                    Spacer(modifier = Modifier.height(18.dp))
                }

                if (trackName.isNotEmpty()
                    && prefs.getBoolean("ambient_media_controls", false)) {
                    AODMediaControlsRow(isPlaying = isPlaying, trackName = trackName)
                    Spacer(modifier = Modifier.height(18.dp))
                }

                if (showCharging) {
                    if (isCharging) {
                        AODChargingRow(
                            level = batteryLevel,
                            watts = chargingWatts,
                            label = chargingLabel,
                            eta   = remainingTime
                        )
                    } else {
                        AODBatteryRow(level = batteryLevel, remainingTime = remainingTime)
                    }
                }
            }
        }
    }
}
@Composable
fun AODClock(
    time:       String,
    fontFamily: FontFamily,
    fontSize:   Float = 80f
) {
    Text(
        text          = time,
        fontSize      = fontSize.sp,
        fontWeight    = FontWeight.Thin,
        color         = Color.White,
        fontFamily    = fontFamily,
        letterSpacing = 4.sp,
        modifier      = Modifier.fillMaxWidth(),
        textAlign     = TextAlign.Center
    )
}
@Composable
fun AODNotificationList(notifications: List<AODNotification>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(notifications, key = { it.id }) { notif ->
            AODNotificationItem(notif)
        }
    }
}

@Composable
fun AODNotificationItem(notif: AODNotification) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(notif.id) {
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
                overflow   = TextOverflow.Ellipsis
            )
            if (notif.body.isNotEmpty()) {
                Text(
                    text     = notif.body,
                    color    = Color.White.copy(0.7f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun AODMediaRow(trackName: String, albumName: String) {
    val displayText = if (albumName.isNotEmpty()) "$trackName · $albumName" else trackName

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text       = displayText,
            color      = Color.White,
            fontSize   = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            textAlign  = TextAlign.Center
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
fun AODChargingRow(level: Int, watts: String, label: String, eta: String) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.5f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                fontSize = 16.sp
            )
        }
        if (eta.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text     = eta,
                color    = Color.White.copy(0.5f),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun AODBatteryRow(level: Int, remainingTime: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text       = "$level%",
            color      = Color.White,
            fontSize   = 15.sp,
            fontWeight = FontWeight.Medium
        )
        if (remainingTime.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text     = remainingTime,
                color    = Color.White.copy(0.7f),
                fontSize = 18.sp
            )
        }
    }

}
@Composable
fun AODMediaControlsRow(
    isPlaying: Boolean,
    trackName: String
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

    LaunchedEffect(showSlider) {
        if (showSlider) {
            delay(2000L)
            showSlider = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
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
                        overflow = TextOverflow.Ellipsis
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
                        onDragEnd = { },
                        onDragCancel = { showSlider = false }
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
fun AODOverlayScreen(isAlwaysOn: Boolean, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        if (!isAlwaysOn) {
            delay(10_000L)
            AODService.getService()?.lockDevice()
            onDismiss()
        }
    }
    AODScreen()
}