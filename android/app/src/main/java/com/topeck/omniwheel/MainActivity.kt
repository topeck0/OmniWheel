package com.topeck.omniwheel

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
                                // Double-back-press: first press goes to connection screen
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
 * Main controller screen — redesigned layout:
 *
 * Layout (landscape, top to bottom):
 * - Status bar (thin, 26dp) — connection info, gyro indicator (no toggle, no exit)
 * - Horizontal pedals row (GAS + BRAKE, optional CLUTCH) — above the wheel
 * - Main area: Steering wheel (left, ~55%) + Button grid (right, ~45%)
 * - Bottom button row
 *
 * Double-back-press returns to connection screen (doesn't exit the app).
 * Gyroscope is enabled/disabled from Settings, not from the controller UI.
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
    val buttons = remember { defaultButtons() }
    var lastPacketCount by remember { mutableIntStateOf(0) }

    // Read gyro enabled from settings
    var gyroEnabled by remember { mutableStateOf(settings.gyroEnabled) }
    
    // Double-back-press state
    var backPressedOnce by remember { mutableStateOf(false) }
    
    // Handle double-back-press to go to connection screen
    BackHandler(enabled = true) {
        if (backPressedOnce) {
            backPressedOnce = false
            onBack()
        } else {
            backPressedOnce = true
            // Reset after 2 seconds if no second press
            kotlinx.coroutines.GlobalScope.launch {
                delay(2000)
                backPressedOnce = false
            }
        }
    }

    // Apply settings to components
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
        
        // Auto-enable gyro if setting is on
        if (settings.gyroEnabled && gyroManager.isAvailable) {
            gyroManager.enable()
            inputSender.gyroActive = true
        }
    }

    // Re-read gyro setting when returning to this screen
    LaunchedEffect(connectedIp) {
        gyroEnabled = settings.gyroEnabled
        if (gyroEnabled && gyroManager.isAvailable) {
            gyroManager.enable()
            inputSender.gyroActive = true
        }
    }

    // Lightweight sync loop for pedals and buttons
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
            // Status bar (simplified — no GYRO toggle, no EXIT)
            ControllerStatusBar(
                gyroAvailable = gyroManager.isAvailable,
                gyroActive = gyroEnabled && gyroManager.isEnabled,
                connectedIp = connectedIp,
                packetCount = if (settings.showPacketCounter) lastPacketCount else -1,
            )

            // Horizontal pedals ABOVE the steering wheel
            HorizontalPedalsRow(
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
            )

            // Main area: steering wheel (left) + button grid (right)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF0F0F1A))
                    .padding(start = 2.dp, end = 2.dp, bottom = 2.dp)
            ) {
                // Steering wheel (left ~55%)
                Box(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    SteeringWheelView(
                        viewModel = steeringViewModel,
                        isGyroActive = gyroEnabled && gyroManager.isEnabled,
                        showAngleText = settings.showAngleText,
                    )
                }

                // Button grid (right ~45%) — two columns of 6
                Column(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Top row: buttons 1-6
                    ButtonRow(
                        buttons = buttons.take(6),
                        activeButtons = activeButtons.value,
                        onButtonPress = { btnId, pressed ->
                            activeButtons.value = if (pressed) {
                                activeButtons.value + btnId
                            } else {
                                activeButtons.value - btnId
                            }
                        },
                        buttonSize = settings.buttonSizeTop,
                        hapticEnabled = settings.hapticFeedback
                    )
                    
                    // Bottom row: buttons 7-12
                    ButtonRow(
                        buttons = buttons.drop(6),
                        activeButtons = activeButtons.value,
                        onButtonPress = { btnId, pressed ->
                            activeButtons.value = if (pressed) {
                                activeButtons.value + btnId
                            } else {
                                activeButtons.value - btnId
                            }
                        },
                        buttonSize = settings.buttonSizeBottom,
                        hapticEnabled = settings.hapticFeedback
                    )
                }
            }
        }
        
        // Toast for back press
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