package com.hktamizhan.hkaod

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

class AODActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupLockscreen()
        setupWindowFlagsEarly()
        overridePendingTransition(0, 0)
        setContent {
            AODActivityScreen()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }


    private fun setupWindowFlagsEarly() {
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON   or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON   or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN       or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.statusBarColor     = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun setupLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // Deliberately NOT dismissing the keyguard: the AOD must draw on
            // top of the lock screen, not unlock the device.
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: shows briefly then auto-hides
        // This is the correct behavior for Android 13 — BEHAVIOR_DEFAULT
        // does NOT block gesture nav on API 33
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars    = false
        controller.isAppearanceLightNavigationBars = false
    }
}

@Composable
fun AODActivityScreen() {
    val context  = LocalContext.current
    val activity = context as? Activity
    val view     = LocalView.current
    val prefs    = remember { context.getSharedPreferences("HKAOD_PREFS", Context.MODE_PRIVATE) }
    val isAlwaysOn = remember { prefs.getBoolean("ambient_enabled", false) }

    // Re-hide bars from Compose side — covers edge cases like
    // notification panel being pulled down and not auto-closing
    DisposableEffect(Unit) {
        val window = (context as ComponentActivity).window
        val originalBrightness = window.attributes.screenBrightness

        // Set to minimum (0f = darkest, but never fully off)
        window.attributes = window.attributes.also {
            it.screenBrightness = 0f
        }

        onDispose {
            // ✅ Restore original brightness when AOD closes
            window.attributes = window.attributes.also {
                it.screenBrightness = originalBrightness
            }
        }
    }
    LaunchedEffect(Unit) {
        val window = (context as ComponentActivity).window
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (!isAlwaysOn) {
            val timeoutMs = (prefs.getInt("aod_timeout_seconds", 10) * 1000L)
                .coerceAtLeast(2_000L)
            delay(timeoutMs)
            // Prefer locking the device via the accessibility service; if it isn't
            // available, just finish the activity. Either way the screen closes.
            AODService.getService()?.lockDevice()
            activity?.finish()
        }
    }


    BackHandler(enabled = true) {}
    AODScreen(onDismiss = { activity?.finish() })
}