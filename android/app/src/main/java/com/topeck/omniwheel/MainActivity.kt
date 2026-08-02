package com.topeck.omniwheel

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.network.DiscoveryClient
import com.topeck.omniwheel.network.InputSender
import com.topeck.omniwheel.sensor.GyroManager
import com.topeck.omniwheel.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

enum class AppScreen { CONNECTION, CONTROLLER, SETTINGS }

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
                    when (screen) {
                        AppScreen.CONNECTION -> ConnectionScreen(
                            discovery = discovery,
                            inputSender = inputSender,
                            onConnected = { ip ->
                                connectedIp = ip
                                screen = AppScreen.CONTROLLER
                            },
                            onOpenSettings = { screen = AppScreen.SETTINGS }
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
                            onBack = { screen = AppScreen.CONNECTION }
                        )
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
 * Controller screen layout matching reference:
 *
 * LEFT SIDE:
 *   Top: Buttons 5, 6, 7, 18 (row)
 *   Below: Button 4 (left of wheel), Button 8 (right of wheel, below 18)
 *   Center: Steering wheel
 *   Bottom-left: Button 14
 *
 * RIGHT SIDE:
 *   Top: Buttons 1, 3, 2 (large row)
 *   Middle: Buttons 10, 15, 9
 *   Bottom: Buttons 11, 12, 13 (13 wide), Button 17
 *
 * FAR RIGHT:
 *   Two vertical sliders (Brake, Gas, optional Clutch)
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
    var lastPacketCount by remember { mutableIntStateOf(0) }
    var gyroEnabled by remember { mutableStateOf(settings.gyroEnabled) }
    var backPressedOnce by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                (throttle * settings.throttleMaxByte / 255f).toInt().coerceIn(0, 255).toByte() else 0
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

            // Main content: left (wheel+buttons) | center (buttons) | right (pedals)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // ===== LEFT SIDE: wheel area with surrounding buttons =====
                Box(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                ) {
                    // Top row: 5, 6, 7, 18
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        VButtonView(btnMap[5]!!, isActive(btnMap[5]!!.action), onBtn, 38, settings.hapticFeedback)
                        VButtonView(btnMap[6]!!, isActive(btnMap[6]!!.action), onBtn, 38, settings.hapticFeedback)
                        VButtonView(btnMap[7]!!, isActive(btnMap[7]!!.action), onBtn, 38, settings.hapticFeedback)
                        VButtonView(btnMap[18]!!, isActive(btnMap[18]!!.action), onBtn, 42, settings.hapticFeedback)
                    }

                    // Button 4 (lower-left of top row)
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 2.dp, top = 30.dp)
                    ) {
                        VButtonView(btnMap[4]!!, isActive(btnMap[4]!!.action), onBtn, 38, settings.hapticFeedback)
                    }

                    // Button 8 (below button 18)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 2.dp, top = 34.dp)
                    ) {
                        VButtonView(btnMap[8]!!, isActive(btnMap[8]!!.action), onBtn, 42, settings.hapticFeedback)
                    }

                    // Steering wheel (center)
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 20.dp, bottom = 10.dp)
                            .fillMaxWidth(0.95f)
                            .fillMaxHeight(0.7f),
                        contentAlignment = Alignment.Center
                    ) {
                        SteeringWheelView(
                            viewModel = steeringViewModel,
                            isGyroActive = gyroEnabled && gyroManager.isEnabled,
                            showAngleText = settings.showAngleText,
                        )
                    }

                    // Button 14 (bottom-left)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 2.dp, bottom = 4.dp)
                    ) {
                        VButtonView(btnMap[14]!!, isActive(btnMap[14]!!.action), onBtn, 38, settings.hapticFeedback)
                    }
                }

                // ===== CENTER-RIGHT: button grid =====
                Column(
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Top row: 1, 3, 2 (larger buttons)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VButtonView(btnMap[1]!!, isActive(btnMap[1]!!.action), onBtn, 44, settings.hapticFeedback)
                        VButtonView(btnMap[3]!!, isActive(btnMap[3]!!.action), onBtn, 44, settings.hapticFeedback)
                        VButtonView(btnMap[2]!!, isActive(btnMap[2]!!.action), onBtn, 44, settings.hapticFeedback)
                    }

                    // Middle row: 10, 15, 9
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VButtonView(btnMap[10]!!, isActive(btnMap[10]!!.action), onBtn, 40, settings.hapticFeedback)
                        VButtonView(btnMap[15]!!, isActive(btnMap[15]!!.action), onBtn, 40, settings.hapticFeedback)
                        VButtonView(btnMap[9]!!, isActive(btnMap[9]!!.action), onBtn, 40, settings.hapticFeedback)
                    }

                    // Bottom area: 11, 12 side by side, 13 wide below, 17 to the right
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VButtonView(btnMap[11]!!, isActive(btnMap[11]!!.action), onBtn, 38, settings.hapticFeedback)
                        VButtonView(btnMap[12]!!, isActive(btnMap[12]!!.action), onBtn, 38, settings.hapticFeedback)
                        VButtonView(btnMap[17]!!, isActive(btnMap[17]!!.action), onBtn, 38, settings.hapticFeedback)
                    }
                    // Button 13 (wide, centered below 11+12)
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        WideButtonView(btnMap[13]!!, isActive(btnMap[13]!!.action), onBtn, widthDp = 140, heightDp = 34, hapticEnabled = settings.hapticFeedback)
                    }
                }

                // ===== FAR RIGHT: vertical pedal sliders =====
                VerticalPedalsColumn(
                    throttle = throttle,
                    brake = brake,
                    clutch = clutch,
                    clutchEnabled = settings.clutchEnabled,
                    onThrottleChange = { throttle = it },
                    onBrakeChange = { brake = it },
                    onClutchChange = { clutch = it },
                    onThrottleRelease = { if (settings.pedalReturnOnRelease) throttle = 0f },
                    onBrakeRelease = { brake = 0f },
                    onClutchRelease = { clutch = 0f },
                    pedalWidthDp = settings.pedalWidth
                )
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
