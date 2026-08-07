package com.topeck.omniwheel

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.network.DiscoveryClient
import com.topeck.omniwheel.network.InputSender
import com.topeck.omniwheel.sensor.GyroManager
import com.topeck.omniwheel.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

enum class AppScreen { CONNECTION, CONTROLLER, SETTINGS, HUD_EDITOR, LOGS }

class MainActivity : ComponentActivity() {

    private lateinit var discovery: DiscoveryClient
    private lateinit var inputSender: InputSender
    private lateinit var gyroManager: GyroManager
    private lateinit var settings: SettingsManager
    private val appLogs = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        discovery = DiscoveryClient(this)
        inputSender = InputSender(this)
        gyroManager = GyroManager(this)
        settings = SettingsManager(this)

        inputSender.onLog = { msg ->
            synchronized(appLogs) {
                appLogs.add("[I] $msg")
                if (appLogs.size > 100) appLogs.removeFirst()
            }
        }
        discovery.onLog = { msg ->
            synchronized(appLogs) {
                appLogs.add("[D] $msg")
                if (appLogs.size > 100) appLogs.removeFirst()
            }
        }

        setContent {
            OmniWheelTheme {
                var screen by remember { mutableStateOf(AppScreen.CONNECTION) }
                var connectedIp by remember { mutableStateOf("") }

                Surface(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F1A)),
                    color = Color(0xFF0F0F1A)
                ) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            // Determine direction based on old vs new screen
                            val entering = if (targetState == AppScreen.CONTROLLER && initialState == AppScreen.CONNECTION) {
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> fullWidth },
                                    animationSpec = tween(350, easing = FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = tween(250, delayMillis = 50))
                            } else if (targetState == AppScreen.SETTINGS && initialState == AppScreen.CONNECTION) {
                                slideInVertically(
                                    initialOffsetY = { fullHeight -> fullHeight },
                                    animationSpec = tween(400, easing = FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = tween(200, delayMillis = 80))
                            } else if (targetState == AppScreen.CONNECTION && initialState == AppScreen.CONTROLLER) {
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> -fullWidth / 3 },
                                    animationSpec = tween(350, easing = FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = tween(250))
                            } else if (targetState == AppScreen.CONNECTION && initialState == AppScreen.SETTINGS) {
                                slideInVertically(
                                    initialOffsetY = { fullHeight -> -fullHeight / 3 },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = tween(200))
                            } else {
                                fadeIn(animationSpec = tween(300)) + scaleIn(
                                    initialScale = 0.96f,
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                )
                            }

                            val exiting = if (initialState == AppScreen.CONNECTION && targetState == AppScreen.CONTROLLER) {
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> -fullWidth / 4 },
                                    animationSpec = tween(300, easing = FastOutLinearInEasing)
                                ) + fadeOut(animationSpec = tween(250))
                            } else if (initialState == AppScreen.CONNECTION && targetState == AppScreen.SETTINGS) {
                                slideOutVertically(
                                    targetOffsetY = { fullHeight -> -fullHeight / 4 },
                                    animationSpec = tween(300, easing = FastOutLinearInEasing)
                                ) + fadeOut(animationSpec = tween(200))
                            } else if (initialState == AppScreen.CONTROLLER && targetState == AppScreen.CONNECTION) {
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> fullWidth },
                                    animationSpec = tween(300, easing = FastOutLinearInEasing)
                                ) + fadeOut(animationSpec = tween(200))
                            } else if (initialState == AppScreen.SETTINGS && targetState == AppScreen.CONNECTION) {
                                slideOutVertically(
                                    targetOffsetY = { fullHeight -> fullHeight },
                                    animationSpec = tween(350, easing = FastOutLinearInEasing)
                                ) + fadeOut(animationSpec = tween(200))
                            } else {
                                fadeOut(animationSpec = tween(250)) + scaleOut(
                                    targetScale = 0.96f,
                                    animationSpec = tween(250, easing = FastOutLinearInEasing)
                                )
                            }

                            entering togetherWith exiting
                        },
                        label = "screenTransition"
                    ) { currentScreen ->
                        when (currentScreen) {
                             AppScreen.CONNECTION -> ConnectionScreen(
                                 discovery = discovery,
                                 inputSender = inputSender,
                                 settings = settings,
                                 onConnected = { ip ->
                                     connectedIp = ip
                                     screen = AppScreen.CONTROLLER
                                 },
                                 onOpenSettings = { screen = AppScreen.SETTINGS },
                                 onOpenHudEditor = { screen = AppScreen.HUD_EDITOR }
                             )
                             AppScreen.CONTROLLER -> ControllerScreen(
                                 inputSender = inputSender,
                                 gyroManager = gyroManager,
                                 settings = settings,
                                 connectedIp = connectedIp,
                                 onBack = {
                                     gyroManager.disable()
                                     inputSender.disconnect()
                                     connectedIp = ""
                                     screen = AppScreen.CONNECTION
                                 }
                             )
                             AppScreen.SETTINGS -> SettingsScreen(
                                 settings = settings,
                                 onBack = { screen = AppScreen.CONNECTION },
                                 onOpenLogs = { screen = AppScreen.LOGS }
                             )
                             AppScreen.HUD_EDITOR -> HudEditorScreen(
                                 settings = settings,
                                 onBack = { screen = AppScreen.CONNECTION }
                             )
                             AppScreen.LOGS -> LogsScreen(
                                 logs = appLogs,
                                 onBack = { screen = AppScreen.SETTINGS }
                             )
                        }
                    }
                }
            }
        }
    }

    private fun setupFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    override fun onResume() { super.onResume(); setupFullscreen() }
    override fun onDestroy() {
        super.onDestroy()
        discovery.stop()
        inputSender.disconnect()
        gyroManager.disable()
    }
}

@Composable
fun OmniWheelTheme(content: @Composable () -> Unit) {
    val darkColorScheme = darkColorScheme(
        primary = Color(0xFF6366F1),
        onPrimary = Color.White,
        secondary = Color(0xFF22D3EE),
        background = Color(0xFF0F0F1A),
        surface = Color(0xFF1A1A2E),
        onBackground = Color.White,
        onSurface = Color(0xFFE0E0E0)
    )
    MaterialTheme(colorScheme = darkColorScheme, content = content)
}

/**
 * Controller screen. Widget positions/sizes come from the shared HUD layout
 * (see HudLayout.kt / defaultControllerLayout) so the game screen and the
 * HUD editor always render identical positions. Editing in the HUD editor
 * persists via SettingsManager.saveHudLayout and is reloaded here.
 */
@Composable
fun ControllerScreen(
    inputSender: InputSender,
    gyroManager: GyroManager,
    settings: SettingsManager,
    connectedIp: String,
    onBack: () -> Unit
) {
    val steeringViewModel = rememberSteeringViewModel()
    var throttle by remember { mutableStateOf(0f) }
    var brake by remember { mutableStateOf(0f) }
    var clutch by remember { mutableStateOf(0f) }
    val activeButtons = remember { mutableStateOf(setOf<Int>()) }
    val btnMap = remember { allButtons() }
    val hudLayout = remember { settings.loadHudLayout() }
    var lastPacketCount by remember { mutableIntStateOf(0) }
    var gyroEnabled by remember { mutableStateOf(settings.gyroEnabled) }
    var backPressedOnce by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Push our device metadata + layout to the PC so the preview is a live copy.
    // The authoritative full layout (chunked) is re-sent on EVERY fast cycle
    // right after connecting, and periodically afterwards, so the PC always
    // completes its reassembly even when individual UDP chunks get dropped on
    // WiFi (a single lost chunk would otherwise leave the PC on the default
    // layout forever). Only widgets that changed are additionally streamed.
    LaunchedEffect(Unit) {
        val startMs = System.currentTimeMillis()
        while (true) {
            val loopStart = System.currentTimeMillis()
            try {
                val bbm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val cap = bbm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (cap != null && cap in 0..100) {
                    inputSender.metaBatteryPercent = cap
                }
                inputSender.metaMaxAngle = settings.steeringMaxAngle
                inputSender.metaDeviceType = Build.MODEL.ifBlank { "Android Phone" }
                inputSender.metaClutchEnabled = settings.clutchEnabled
                inputSender.sendMetaPacket()

                // Never transmit the clutch widget when it is disabled.
                val sendList = if (settings.clutchEnabled) hudLayout
                    else hudLayout.filterNot { it.id == "clutch" }
                val fullJson = widgetListToJson(sendList)
                val sendJsons = sendList.map { it.toJson().toString() }

                // Authoritative full layout: fast cadence while the connection
                // is young so any dropped chunk is repaired by the next burst.
                inputSender.sendFullLayout(fullJson)
                inputSender.syncLayout(sendJsons)

                val elapsed = System.currentTimeMillis() - startMs
                val delayMs = if (elapsed < 3000) 400L else 30_000L
                val spent = System.currentTimeMillis() - loopStart
                delay((delayMs - spent).coerceAtLeast(50L))
            } catch (t: Throwable) {
                // A single bad iteration must never kill the sync loop.
                inputSender.onLog?.invoke("Sync loop error: ${t.message}")
                Log.e("OmniWheel", "Layout sync loop", t)
                delay(1000L)
            }
        }
    }

    val onBtn: (Int, Boolean) -> Unit = { btnId, pressed ->
        activeButtons.value = if (pressed) {
            activeButtons.value + btnId
        } else {
            activeButtons.value - btnId
        }
    }

    fun isActive(action: Int): Boolean = activeButtons.value.contains(action)

    BackHandler(enabled = true) {
        if (backPressedOnce) {
            backPressedOnce = false
            onBack()
        } else {
            backPressedOnce = true
            scope.launch {
                delay(2000)
                backPressedOnce = false
            }
        }
    }

    LaunchedEffect(Unit) {
        steeringViewModel.maxAngleDeg = settings.steeringMaxAngle.toFloat()
        steeringViewModel.sensitivity = settings.steeringSensitivity
        steeringViewModel.deadzone = settings.steeringDeadzone
        steeringViewModel.smoothing = settings.steeringSmoothing
        steeringViewModel.springStrength = settings.springStrength
        steeringViewModel.damping = settings.springDamping
        steeringViewModel.inputSender = inputSender
        gyroManager.maxTiltDeg = settings.gyroMaxTiltDeg
        gyroManager.sensitivity = settings.gyroSensitivity
        gyroManager.deadzoneDeg = settings.gyroDeadzoneDeg
        gyroManager.filterAlpha = settings.gyroFilterAlpha
        gyroManager.smoothAlpha = settings.gyroSmoothAlpha
        gyroManager.inputSender = inputSender
        gyroManager.onSteeringChanged = { steeringViewModel.setGyroAngle(it) }
        inputSender.sendRateHz = settings.sendRateHz
        if (settings.gyroEnabled && gyroManager.isAvailable) {
            gyroManager.enable()
            inputSender.gyroActive = true
        }
    }

    LaunchedEffect(connectedIp) {
        gyroEnabled = settings.gyroEnabled
        if (gyroEnabled && gyroManager.isAvailable) {
            gyroManager.enable()
            inputSender.gyroActive = true
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            inputSender.throttle = if (settings.pedalReturnOnRelease || throttle > 0f)
                (throttle * settings.throttleMaxByte).toInt().coerceIn(0, 255).toByte() else 0
            inputSender.brake = (brake * 255).toInt().toByte()
            inputSender.clutch = (clutch * 255).toInt().toByte()
            inputSender.activeButtons = activeButtons.value
            lastPacketCount = inputSender.sendCount
            delay(1000L / 240)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ControllerStatusBar(
                gyroAvailable = gyroManager.isAvailable,
                gyroActive = gyroEnabled && gyroManager.isEnabled,
                connectedIp = connectedIp,
                packetCount = if (settings.showPacketCounter) lastPacketCount else -1,
            )

            // Main content rendered from the shared HUD layout (same as HUD editor)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val aw = maxWidth
                val ah = maxHeight
                val density = LocalDensity.current

                // Advertise the exact play-area size so the PC preview preserves
                // the phone's aspect ratio and proportions at any window size.
                LaunchedEffect(aw, ah) {
                    val wpx = with(density) { aw.toPx() }.toInt().coerceAtLeast(1)
                    val hpx = with(density) { ah.toPx() }.toInt().coerceAtLeast(1)
                    if (inputSender.metaScreenWidthPx != wpx || inputSender.metaScreenHeightPx != hpx) {
                        inputSender.metaScreenWidthPx = wpx
                        inputSender.metaScreenHeightPx = hpx
                        inputSender.sendMetaPacket()
                    }
                }

                hudLayout.forEach { widget ->
                    when {
                        widget.isSteering -> HudSlot(widget, aw, ah) {
                            SteeringWheelView(
                                viewModel = steeringViewModel,
                                isGyroActive = gyroEnabled && gyroManager.isEnabled,
                                showAngleText = settings.showAngleText,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        widget.isPedal -> {
                            if (widget.id == "clutch" && !settings.clutchEnabled) return@forEach
                            HudSlot(widget, aw, ah) {
                                when (widget.id) {
                                    "gas" -> PedalView(
                                        "GAS", Color(0xFF22C55E), throttle,
                                        onValueChange = { throttle = it },
                                        onRelease = { if (settings.pedalReturnOnRelease) throttle = 0f },
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    "brake" -> PedalView(
                                        "BRAKE", Color(0xFFEF4444), brake,
                                        onValueChange = { brake = it },
                                        onRelease = { brake = 0f },
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    "clutch" -> PedalView(
                                        "CLUTCH", Color(0xFFF59E0B), clutch,
                                        onValueChange = { clutch = it },
                                        onRelease = { clutch = 0f },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        else -> HudSlot(widget, aw, ah) {
                            val action = widget.vJoyBtn
                            val b = btnMap[action] ?: VButton(action, widget.label, action = action)
                            HudButtonView(
                                button = b,
                                isActive = isActive(action),
                                onPressed = onBtn,
                                hapticEnabled = settings.hapticFeedback
                            )
                        }
                    }
                }
            }
        }

        // Back-press toast
        if (backPressedOnce) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .background(Color(0xFF333333).copy(alpha = 0.8f), MaterialTheme.shapes.medium)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Press back again to disconnect",
                    fontSize = 12.sp,
                    color = Color.White
                )
            }
        }
    }
}
