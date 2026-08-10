package com.hktamizhan.hkaod
import androidx.navigation.NavHostController
import android.provider.Settings
import android.provider.OpenableColumns
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.ui.graphics.Path
import androidx.core.app.NotificationCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import androidx.compose.runtime.collectAsState


val HKAOD_Red = Color(0xFFEC6666)
val HKAOD_DarkSurface = Color(0xC4574D4D)
val hkFontFamily = FontFamily(
    Font(R.font.hksuper, FontWeight.Normal),
    Font(R.font.hksuper, FontWeight.Bold)
)
val hkFontFamily2 = FontFamily(
    Font(R.font.hkjjk, FontWeight.Normal),
    Font(R.font.hkjjk, FontWeight.Bold)
)
val hkFontFamily3 = FontFamily(
    Font(R.font.hkexo, FontWeight.Normal),
    Font(R.font.hkexo, FontWeight.Bold)
)
val hkFontFamily4 = FontFamily(
    Font(R.font.firlest, FontWeight.Normal),
    Font(R.font.firlest, FontWeight.Bold)
)
val hkFontFamily5 = FontFamily(
    Font(R.font.higher, FontWeight.Normal),
    Font(R.font.higher, FontWeight.Bold)
)
val hkFontFamily6 = FontFamily(
    Font(R.font.rocky, FontWeight.Normal),
    Font(R.font.rocky, FontWeight.Bold)
)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!isAccessibilityServiceEnabled(this)) {
            Toast.makeText(
                this,
                "                    ⚠️           \nAccessibility Services are turned off",
                Toast.LENGTH_LONG
            ).show()
        }


        setContent {
            val context = LocalContext.current
            val sharedPref = remember { context.getSharedPreferences("HKAOD_PREFS", Context.MODE_PRIVATE) }
            var showOnboarding by remember {
                mutableStateOf(sharedPref.getBoolean("isFirstTime", true))
            }

            MaterialTheme {
                if (showOnboarding) {
                    OnboardingPager(
                        onFinished = {
                            // Onboarding mudinja udane 'false' nu save panniduvom
                            sharedPref.edit().putBoolean("isFirstTime", false).apply()
                            showOnboarding = false // State-ah update panna auto-va Main Screen varum
                        }
                    )
                } else {
                    MainAppContainer()
                }
            }
        }
    }
}

@Composable
fun MainAppContainer() {
    val navController = rememberNavController()
    var isMenuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "main_settings",
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(700, easing = FastOutSlowInEasing)
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth },
                    animationSpec = tween(700, easing = FastOutSlowInEasing)
                )
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth },
                    animationSpec = tween(700, easing = FastOutSlowInEasing)
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(700, easing = FastOutSlowInEasing)
                )
            }
        )
         {
            composable("main_settings") {
                MainSettingsScreen(
                    navController = navController,
                    onMenuClick = { isMenuOpen = true }
                )
            }
            composable("lift_to_wake_screen") {
                FeatureSettingsScreen(
                    title = "Lift to Wake",
                    description = "Wake AOD when the device is lifted",
                    prefsKey = "lift_enabled",
                    showSensitivity = false,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("wave_to_wake_screen") {
                FeatureSettingsScreen(
                    title = "Wave to Wake",
                    description = "Wave or Touch over the proximity sensor to wake AOD",
                    prefsKey = "wave_enabled",
                    showSensitivity = false,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("Always_On_Ambient") {
                FeatureSettingsScreen(
                    title = "Ambient Display",
                    description = "Keep display active with low power",
                    prefsKey = "ambient_enabled",
                    showSensitivity = false,
                    onBack = { navController.popBackStack() }
                )
            }
             composable("Shake_for_AOD") {
                 FeatureSettingsScreen(
                     title = "Shake to Wake",
                     description = "Shake works based on yours sensitivity",
                     prefsKey = "shake_enabled",
                     showSensitivity = true,
                     onBack = { navController.popBackStack() }
                 )
             }
            composable("pocket_detection_screen") {
                FeatureSettingsScreen(
                    title = "Pocket Detection",
                    description = "Turn off display in pocket, wake when removed",
                    prefsKey = "pocket_detection_enabled",
                    showSensitivity = false,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("ambient_settings_screen") {
                AmbientSettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        SideMenuOverlay(
            visible = isMenuOpen,
            onClose = { isMenuOpen = false }
        ) {
            SideMenuDrawer(
                onClose = { isMenuOpen = false },
                onNavigate = { route ->
                    isMenuOpen = false  // ✅ Close BEFORE navigating
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
@Composable
fun MainSettingsScreen(
    navController: NavHostController,
    onMenuClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(110.dp))


            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = null,
                modifier = Modifier
                    .size(180.dp)
                    .align(Alignment.CenterHorizontally),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(30.dp))


            HKAODCard("Lift To Wake", "See every stuff at a single lift", R.drawable.upload, onClick = {
                navController.navigate("lift_to_wake_screen")
            })

            HKAODCard("Wave To Wake", "Wave to see the essentials", R.drawable.hand, onClick = {
                navController.navigate("wave_to_wake_screen")
            })

            HKAODCard("Always On Ambient", "Always I'll appear", R.drawable.starfilled, onClick = {
                navController.navigate("Always_On_Ambient")
            })

            HKAODCard("Shake to wake", "Shake the device to wake instantly to AOD", R.drawable.shake, onClick = {
                navController.navigate("Shake_for_AOD")
            })

            HKAODCard("Pocket Detection", "Smart proximity-based display control", R.drawable.hand, onClick = {
                navController.navigate("pocket_detection_screen")
            })

            Spacer(modifier = Modifier.height(50.dp))
        }


        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, Color(0xFFEC6666), CircleShape)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.hkdevpic),
                    contentDescription = "Dev Info",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
fun HKAODCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                onClick()
            },
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(0.12f)
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(HKAOD_Red, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color.White.copy(0.6f), fontSize = 12.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.White)
        }
    }
}
@Composable
fun SideMenuOverlay(visible: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    // 1. Smooth Scrim (Background Dim) Animation
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.7f else 0f,
        animationSpec = tween(durationMillis = 800, easing = LinearOutSlowInEasing),
        label = "alphaDim"
    )

    // 2. Smooth Slide Animation with Spring (Premium Feel)
    val slideOffset by animateFloatAsState(
        targetValue = if (visible) 0f else -1f,
        animationSpec = tween(
            durationMillis = 800,
            easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1f)
        ),
        label = "slideAnim"
    )

    if (alpha > 0.01f) {
        Box(modifier = Modifier.fillMaxSize()) {

            // BACKGROUND DIM: graphicsLayer use panna frame drop aagaadhu
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha }
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onClose() })
                    }
            )

            // SLIDING CONTENT: Manual offset with graphicsLayer is faster than AnimatedVisibility
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f) // Full width kulla content reveal aagum
                    .fillMaxHeight()
                    .graphicsLayer {
                        // it.size.width use panni smooth-ah slide panrom
                        translationX = slideOffset * size.width
                    }
            ) {
                content()
            }
        }
    }
}
@Composable
fun SideMenuDrawer(onClose: () -> Unit,onNavigate: (String) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(topEnd = 32.dp, bottomEnd = 32.dp),
        tonalElevation = 8.dp, // Premium shadow effect
        shadowElevation = 12.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // 1. BACKGROUND IMAGE (Logo as Watermark)
            Image(
                painter = painterResource(id = R.drawable.sidebg),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .statusBarsPadding()
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }

                Spacer(modifier = Modifier.height(30.dp))
                Text(
                    text = "HKAOD (The LAB) ",
                    fontFamily = hkFontFamily,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(30.dp))


                // Menu Items
                SideMenuItem("Ambient Display", R.drawable.amb) {
                    onNavigate("ambient_settings_screen")
                }
                SideMenuItem("Battery Optimization", R.drawable.battery)
                SideMenuItem("Rate App", R.drawable.rate)
                SideMenuItem("About Us", R.drawable.about)

            }

        }

    }
}
@Composable
fun SideMenuItem(text: String,iconRes: Int, onClick: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) { onClick() },
        color = HKAOD_DarkSurface.copy(0.6f),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(modifier = Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(
                painter = painterResource(id = iconRes), // Ippo unga own icon inga reveal aagum
                contentDescription = null,
                tint = Color.Unspecified, // Vector-oda original color venum na 'Unspecified' kudunga
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.Medium)
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.White.copy(0.3f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
@Composable
fun OnboardingPager(onFinished: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        HorizontalPager(state = pagerState) { page ->
            // Inga unga 5 screens-oda UI-ah switch panni kaatunga
            when (page) {
                0 -> WelcomeScreen(onNextClick = {
                    scope.launch { pagerState.animateScrollToPage(
                        page = 1,
                        animationSpec = tween(
                            durationMillis = 700,
                            easing = FastOutSlowInEasing
                        )
                    ) }
                })

                1 -> NotificationRequestScreen(onNextClick = {
                    scope.launch {pagerState.animateScrollToPage(
                        page = 2,
                        animationSpec = tween(
                            durationMillis = 700,
                            easing = FastOutSlowInEasing
                        )
                    ) }
                })

                2 -> AccessibilityRequestScreen(onNextClick = {
                    scope.launch { pagerState.animateScrollToPage(
                        page = 3,
                        animationSpec = tween(
                            durationMillis = 700,
                            easing = FastOutSlowInEasing
                        )
                    ) }
                })

                3 -> BatteryOptimizationScreen(onNextClick = {
                    scope.launch { pagerState.animateScrollToPage(
                        page = 4,
                        animationSpec = tween(
                            durationMillis = 700,
                            easing = FastOutSlowInEasing
                        )
                    ) }
                })

                4 -> FinalScreen(onNextClick = {
                    onFinished()
                })

            }
        }
    }
}
@Composable
fun WelcomeScreen(onNextClick: () -> Unit) {
    // 1. Lottie Composition structure (Unga JSON asset name: welcome_anim)
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.welcome))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        speed = 0.5f
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp), // Sides-la side gap
        horizontalAlignment = Alignment.CenterHorizontally, // Horizontal align Center
        verticalArrangement = Arrangement.Center // Vertical align Center (Nadula kondu varum)
    ) {

        // 3. CENTER LOTTIE ANIMATION
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = Modifier
                .size(300.dp) // Proper size for welcome animation
                .padding(bottom = 30.dp) // Text-kum gap
        )

        // 4. CONTEXT MESSAGES (Heading)
        Text(
            text = "Welcome to HKAOD",
            fontSize = 32.sp, // Bold heading size
            fontFamily = hkFontFamily3,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(15.dp))

        // 5. CONTEXT MESSAGES (Description)
        Text(
            text = "Discover the ultimate Ambient On Display experience with premium customization and features.",
            fontSize = 17.sp,
            color = Color.White.copy(alpha = 0.8f), // 80% opacity for better read
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 25.dp), // Text sides-la gap
            lineHeight = 24.sp // Text lines-ku gap
        )

        Spacer(modifier = Modifier.height(50.dp))

        // 6. NEXT BUTTON (Specific for this screen)
        Button(
            onClick = onNextClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC6666)),
            shape = RoundedCornerShape(24.dp), // Premium rounded corners
            modifier = Modifier
                .fillMaxWidth(0.6f) // Button size
                .height(55.dp) // Bold button height
        ) {
            Text(
                text = "Next",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 3.sp
            )
        }
    }
}
@Composable
fun NotificationRequestScreen(onNextClick: () -> Unit) {
    val context = LocalContext.current
    // 1. Lottie for Notification (Example: A ringing bell or message bubble)
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.notifications))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        speed = 0.7f
    )
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
        // The core step for this screen is enabling notification *listener*
        // access so the AOD can read incoming notifications. Open that page
        // after the runtime dialog is dismissed (not on top of it), then advance.
        try {
            val notificationIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(notificationIntent)
        } catch (_: Exception) {
        }
        onNextClick()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 2. CENTER ANIMATION
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = Modifier.size(280.dp).padding(bottom = 20.dp)
        )

        // 3. TEXT CONTENT
        Text(
            text = "Stay Connected",
            fontSize = 28.sp,
            fontFamily = hkFontFamily3,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Allow HKAOD to read incoming notifications to display them on realtime.",
            fontSize = 16.sp,
            color = Color.White.copy(0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.height(40.dp))

        // 4. PERMISSION BUTTON
        Button(
            onClick = {
                // On Android 13+ ask for POST_NOTIFICATIONS first; the listener
                // settings page is then opened from the permission-result callback.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    try {
                        val notificationIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        notificationIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(notificationIntent)
                    } catch (_: Exception) {
                    }
                    onNextClick()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC6666)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(0.6f).height(55.dp)
        ) {
            Text("GRANT PERMISSION", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}
// Reads the picked font file's bytes, validates the extension, and hands it to
// the repository. Returns the font's display name on success, or null on failure.
private fun importClockFontFromUri(context: Context, uri: Uri): String? {
    val fileName = queryDisplayName(context, uri) ?: "Custom font"
    val lower = fileName.lowercase()
    if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return null

    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    } ?: return null

    val prettyName = fileName.substringBeforeLast('.', fileName)
    return ClockStyleRepository.importCustomFont(context, bytes, prettyName)
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedServiceName = ComponentName(context, AODService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)

    while (colonSplitter.hasNext()) {
        val componentName = colonSplitter.next()
        if (componentName.equals(expectedServiceName.flattenToString(), ignoreCase = true)) {
            return true
        }
    }
    return false
}
@Composable
fun AccessibilityRequestScreen(onNextClick: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()

    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    LaunchedEffect(lifecycleState) {
        if (lifecycleState == Lifecycle.State.RESUMED) {
            if (isAccessibilityServiceEnabled(context)) {
                onNextClick()
            }
        }
    }

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.databreach))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations  = LottieConstants.IterateForever,
        speed       = 0.7f
    )

    Column(
        modifier            = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LottieAnimation(
            composition = composition,
            progress    = { progress },
            modifier    = Modifier.size(280.dp).padding(bottom = 20.dp)
        )

        Text(
            text       = "System Control",
            fontSize   = 28.sp,
            fontFamily = hkFontFamily3,
            color      = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text      = "Enable Accessibility and other permissions to get services to work",
            fontSize  = 16.sp,
            color     = Color.White.copy(0.7f),
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_PHONE_STATE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                } else {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            },
            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC6666)),
            shape    = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(0.6f).height(55.dp)
        ) {
            Text("ENABLE SERVICE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}
@Composable
fun BatteryOptimizationScreen(onNextClick: () -> Unit) {
    val context = LocalContext.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.power))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        speed = 0.7f
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LottieAnimation(composition = composition, progress = { progress },modifier = Modifier.size(280.dp))

        Text(
            text = "Battery Optimization",
            fontSize = 28.sp,
            fontFamily = hkFontFamily3,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "To prevent Android from stopping HKAOD in the background, please disable battery optimization.",
            fontSize = 16.sp,
            color = Color.White.copy(0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")}
                context.startActivity(intent)
                onNextClick()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC6666)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(0.6f).height(55.dp)
        ) {
            Text("IGNORE OPTIMIZATION", fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
fun FinalScreen(onNextClick: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.checked))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        speed = 0.7f
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = Modifier
                .size(320.dp)
                .padding(bottom = 20.dp)
        )
        Text(
            text = "Ready to go",
            fontSize = 32.sp,
            fontFamily = hkFontFamily3,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "All protocols initiated. Your HKAOD is now active and ready for customization.",
            fontSize = 16.sp,
            color = Color.White.copy(0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 30.dp),
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(50.dp))

        Button(
            onClick = onNextClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC6666)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .height(60.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Get started",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null)
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureSettingsScreen(
    title: String,
    description: String,
    prefsKey: String,
    showSensitivity: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("HKAOD_PREFS", Context.MODE_PRIVATE) }
    var isEnabled by remember { mutableStateOf(prefs.getBoolean(prefsKey, false)) }
    var sensitivity by remember { mutableFloatStateOf(prefs.getFloat("${prefsKey}_sens", 3f)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowLeft, null, tint = Color.White)
            }
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(0.05f)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Service", color = Color.White, fontSize = 18.sp)
                        Text(description, color = Color.White.copy(0.6f), fontSize = 12.sp)
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = {
                            isEnabled = it
                            prefs.edit().putBoolean(prefsKey, it).apply()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFEC6666))
                    )
                }
            }
            if (showSensitivity) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White.copy(0.05f)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Sensitivity", color = Color.White, fontSize = 18.sp)
                        Text("Adjust how quickly the sensor reacts", color = Color.White.copy(0.6f), fontSize = 12.sp)

                        Slider(
                            value = sensitivity,
                            onValueChange = {
                                // Snap to whole numbers so the saved value and the
                                // label stay exactly 1..5 (no fractional stops).
                                val snapped = it.roundToInt().toFloat()
                                sensitivity = snapped
                                prefs.edit().putFloat("${prefsKey}_sens", snapped).apply()
                            },
                            valueRange = 1f..5f,
                            steps = 3, // 3 intermediate stops → discrete 1,2,3,4,5
                            colors = SliderDefaults.colors(thumbColor = Color(0xFFEC6666), activeTrackColor = Color(0xFFEC6666))
                        )
                        Text("${sensitivity.roundToInt()}/5", color = Color.Cyan, modifier = Modifier.align(Alignment.End))
                    }
                }
            }
            Spacer(modifier = Modifier.height(30.dp))
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            )  {
                Text("Tips\n\n*Use Moderate sensitivity level for stable sensing" + "\n*If wave to wake is turned on, Automatically it also acts as pocket detection for better battery backup",
                   color = Color.White.copy(0.6f),
                    fontSize = 20.sp
                    )
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbientSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("HKAOD_PREFS", Context.MODE_PRIVATE) }
    var showWatts by remember { mutableStateOf(prefs.getBoolean("charging_show_watts", true)) }
    var showClockPicker by remember { mutableStateOf(false) }
    val currentStyle by ClockStyleRepository.style.collectAsState()
    val currentSize by ClockStyleRepository.size.collectAsState()
    val customFont by ClockStyleRepository.customFont.collectAsState()
    val useCustomFont by ClockStyleRepository.useCustomFont.collectAsState()
    val customFontName by ClockStyleRepository.customFontName.collectAsState()

    // Font file picker — copies the chosen .ttf/.otf into the app and activates it.
    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val imported = importClockFontFromUri(context, uri)
            if (imported == null) {
                Toast.makeText(
                    context,
                    "Couldn't load that file. Please pick a .ttf or .otf font.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    // States
    var timeFormat by remember { mutableStateOf(prefs.getBoolean("time_format_24", false)) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("ambient_notifications", false)) }
    var mediaIntel by remember { mutableStateOf(prefs.getBoolean("ambient_media_intel", false)) }
    var mediaControls by remember { mutableStateOf(prefs.getBoolean("ambient_media_controls", false)) }
    var volumeSteps by remember { mutableIntStateOf(prefs.getInt("volume_swipe_steps", 3)) }
    var chargingSpeed by remember { mutableStateOf(prefs.getBoolean("ambient_charging_speed", false)) }
    var notificationIntel by remember { mutableStateOf(prefs.getBoolean("ambient_notification_intel", false)) }
    var callIntel by remember { mutableStateOf(prefs.getBoolean("ambient_call_intel", false)) }
    LaunchedEffect(Unit) {
        val savedSize = prefs.getFloat("clock_size", 80f)
        ClockStyleRepository.setSize(savedSize)
        ClockStyleRepository.loadPersistedCustomFont(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // Top Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.KeyboardArrowLeft, null, tint = Color.White)
            }
            Text(
                "Ambient Display",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {

            AmbientCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showClockPicker = true }, // ✅ opens sheet
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ambient display (presets)", color = Color.White, fontSize = 16.sp)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
                }
            }
            if (showClockPicker) {
                ModalBottomSheet(
                    onDismissRequest = { showClockPicker = false },
                    containerColor = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 40.dp)
                    ) {
                        Text(
                            text = "Clock Style",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 20.dp)
                        )

                        ClockStyle.entries.forEach { style ->
                            val isSelected = currentStyle == style
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) {
                                        // Selecting a preset turns the custom font off (and
                                        // persists that so it survives a restart).
                                        prefs.edit().putBoolean("clock_use_custom", false).apply()
                                        ClockStyleRepository.setStyle(style)
                                        showClockPicker = false
                                    },
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) HKAOD_Red.copy(0.2f) else Color.White.copy(0.05f),
                                border = if (isSelected)
                                    androidx.compose.foundation.BorderStroke(1.5.dp, HKAOD_Red)
                                else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "12:00",
                                        color = Color.White,
                                        fontSize = 28.sp,
                                        fontFamily = ClockStyleRepository.fontFamilyFor(style),
                                        fontWeight = FontWeight.Thin
                                    )
                                    Text(
                                        text = style.label,
                                        color = Color.White.copy(0.6f),
                                        fontSize = 13.sp
                                    )
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = HKAOD_Red,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Custom clock font import ──
            AmbientCard {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Custom Clock Font", color = Color.White, fontSize = 16.sp)
                            Text(
                                text = if (useCustomFont && customFontName != null)
                                    "Using: $customFontName"
                                else
                                    "Import a .ttf or .otf font file",
                                color = Color.White.copy(0.6f),
                                fontSize = 12.sp
                            )
                        }
                        if (useCustomFont && customFont != null) {
                            Text(
                                text = "12:00",
                                color = HKAOD_Red,
                                fontSize = 22.sp,
                                fontFamily = customFont
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                fontPicker.launch(
                                    arrayOf(
                                        "font/ttf",
                                        "font/otf",
                                        "application/x-font-ttf",
                                        "application/x-font-otf",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = HKAOD_Red),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.upload),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (useCustomFont) "Change Font" else "Import Font",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (useCustomFont) {
                            Spacer(modifier = Modifier.width(12.dp))
                            TextButton(onClick = { ClockStyleRepository.clearCustomFont(context) }) {
                                Text("Reset to preset", color = Color.White.copy(0.7f))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            AmbientToggleCard(
                label = "Show Watts (off = mA)",
                checked = showWatts,
                onCheckedChange = {
                    showWatts = it
                    prefs.edit().putBoolean("charging_show_watts", it).apply()
                }
            )
            Spacer(modifier = Modifier.height(12.dp))

            AmbientCard {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Clock Size", color = Color.White, fontSize = 16.sp)
                        Text(
                            text = when {
                                currentSize < 50f  -> "XS"
                                currentSize < 65f  -> "S"
                                currentSize < 80f  -> "M"
                                currentSize < 95f  -> "L"
                                else               -> "XL"
                            },
                            color    = HKAOD_Red,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text       = "12:00",
                        color      = Color.White,
                        fontSize   = currentSize.sp,
                        fontFamily = if (useCustomFont && customFont != null) customFont!!
                                     else ClockStyleRepository.fontFamilyFor(currentStyle),
                        fontWeight    = FontWeight.Thin,
                        modifier      = Modifier.fillMaxWidth(),
                        textAlign     = TextAlign.Center,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Slider(
                        value         = currentSize,
                        onValueChange = {
                            ClockStyleRepository.setSize(it)
                            prefs.edit().putFloat("clock_size", it).apply()
                        },
                        valueRange    = 40f..120f,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = SliderDefaults.colors(
                            thumbColor         = HKAOD_Red,
                            activeTrackColor   = HKAOD_Red,
                            inactiveTrackColor = Color.White.copy(0.2f)
                        )
                    )

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("40", color = Color.White.copy(0.4f), fontSize = 11.sp)
                        Text("120", color = Color.White.copy(0.4f), fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 24/12 Hrs Format
            AmbientToggleCard(
                label = "24 Hrs Format",
                checked = timeFormat,
                onCheckedChange = {
                    timeFormat = it
                    prefs.edit().putBoolean("time_format_24", it).apply()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Notifications
            AmbientToggleCard(
                label = "Notifications",
                checked = notifications,
                onCheckedChange = {
                    notifications = it
                    prefs.edit().putBoolean("ambient_notifications", it).apply()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Media Intel
            AmbientToggleCard(
                label = "Media Intel",
                checked = mediaIntel,
                onCheckedChange = {
                    mediaIntel = it
                    prefs.edit().putBoolean("ambient_media_intel", it).apply()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 5. Media Controls
            AmbientToggleCard(
                label = "Media Controls",
                checked = mediaControls,
                onCheckedChange = {
                    mediaControls = it
                    prefs.edit().putBoolean("ambient_media_controls", it).apply()
                }
            )

            if (mediaControls) {
                Spacer(modifier = Modifier.height(12.dp))

                // 5a. Volume swipe steps — controls how far you must
                // drag to change volume (1 = coarse/fast, 5 = fine/precise)
                AmbientCard {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Volume Swipe Steps", color = Color.White, fontSize = 16.sp)
                        Text(
                            text = when (volumeSteps) {
                                1 -> "Fast — big changes per swipe"
                                2 -> "Quick — larger jumps"
                                3 -> "Balanced — moderate steps"
                                4 -> "Fine — precise control"
                                else -> "Finest — smallest steps"
                            },
                            color = Color.White.copy(0.6f),
                            fontSize = 12.sp
                        )

                        Slider(
                            value = volumeSteps.toFloat(),
                            onValueChange = {
                                val snapped = it.roundToInt()
                                volumeSteps = snapped
                                prefs.edit().putInt("volume_swipe_steps", snapped).apply()
                            },
                            valueRange = 1f..5f,
                            steps = 3,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFEC6666),
                                activeTrackColor = Color(0xFFEC6666)
                            )
                        )
                        Text(
                            "$volumeSteps/5",
                            color = Color.Cyan,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Charging Speed Indication
            AmbientToggleCard(
                label = "Charging Speed\nIndication",
                checked = chargingSpeed,
                onCheckedChange = {
                    chargingSpeed = it
                    prefs.edit().putBoolean("ambient_charging_speed", it).apply()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 6. Notification Intel
            AmbientToggleCard(
                label = "Notification Intel",
                checked = notificationIntel,
                onCheckedChange = {
                    notificationIntel = it
                    prefs.edit().putBoolean("ambient_notification_intel", it).apply()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 7. Call Intel
            AmbientToggleCard(
                label = "Call Intel",
                checked = callIntel,
                onCheckedChange = {
                    callIntel = it
                    prefs.edit().putBoolean("ambient_call_intel", it).apply()
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// Reusable card container
@Composable
fun AmbientCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF574D4D).copy(alpha = 0.75f)
    ) {
        content()
    }
}

// Reusable toggle row card
@Composable
fun AmbientToggleCard(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    AmbientCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 16.sp)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = HKAOD_Red.copy(alpha = 0.6f),
                    uncheckedThumbColor = Color.White.copy(0.6f),
                    uncheckedTrackColor = Color.Transparent,
                    uncheckedBorderColor = HKAOD_Red.copy(alpha = 0.5f)
                )
            )
        }
    }
}