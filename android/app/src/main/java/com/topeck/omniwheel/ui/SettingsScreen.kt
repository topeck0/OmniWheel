package com.topeck.omniwheel.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.R
import com.topeck.omniwheel.SettingsManager
import com.topeck.omniwheel.widgetListToJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings screen with organized sections.
 */
@Composable
fun SettingsScreen(
    settings: SettingsManager,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit
) {
    var expandedSection by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var exportedPath by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    BackHandler {
        onBack()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF080812))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color(0xFF888888),
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onBack() }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Settings",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE0E0E0)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "v0.8.4-alpha",
                fontSize = 10.sp,
                color = Color(0xFF444444)
            )
        }
        
        // Scrollable settings sections
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // STEERING SECTION
            SettingsSection(
                title = "Steering",
                icon = "SR",
                color = Color(0xFF6366F1),
                isExpanded = expandedSection == "steering",
                onToggle = { expandedSection = if (expandedSection == "steering") null else "steering" },
                scrollState = scrollState,
                coroutineScope = coroutineScope
            ) {
                SettingsSlider(
                    label = "Max Angle",
                    value = settings.steeringMaxAngle.toFloat(),
                    min = 180f, max = 1800f, step = 10f,
                    formatValue = { "${it.toInt()} deg" },
                    onValueChange = { settings.steeringMaxAngle = it.toInt() }
                )
                SettingsSlider(
                    label = "Sensitivity",
                    value = settings.steeringSensitivity,
                    min = 0.2f, max = 3.0f, step = 0.05f,
                    formatValue = { "%.2f".format(it) },
                    onValueChange = { settings.steeringSensitivity = it }
                )
                SettingsSlider(
                    label = "Deadzone",
                    value = settings.steeringDeadzone,
                    min = 0f, max = 0.1f, step = 0.005f,
                    formatValue = { "%.3f".format(it) },
                    onValueChange = { settings.steeringDeadzone = it }
                )
                SettingsSlider(
                    label = "Smoothing",
                    value = settings.steeringSmoothing,
                    min = 0f, max = 0.3f, step = 0.01f,
                    formatValue = { "%.2f".format(it) },
                    onValueChange = { settings.steeringSmoothing = it }
                )
                SettingsSlider(
                    label = "Spring Return Speed",
                    value = settings.springStrength,
                    min = 1f, max = 15f, step = 0.5f,
                    formatValue = { "%.1f".format(it) },
                    onValueChange = { settings.springStrength = it }
                )
                SettingsSlider(
                    label = "Damping",
                    value = settings.springDamping,
                    min = 1f, max = 10f, step = 0.5f,
                    formatValue = { "%.1f".format(it) },
                    onValueChange = { settings.springDamping = it }
                )
            }
            
            // PEDALS SECTION
            SettingsSection(
                title = "Pedals",
                icon = "PD",
                color = Color(0xFF22C55E),
                isExpanded = expandedSection == "pedals",
                onToggle = { expandedSection = if (expandedSection == "pedals") null else "pedals" },
                scrollState = scrollState,
                coroutineScope = coroutineScope
            ) {
                SettingsToggle(
                    label = "Enable Clutch",
                    description = "Show a third clutch pedal next to gas and brake",
                    checked = settings.clutchEnabled,
                    onCheckedChange = { settings.clutchEnabled = it }
                )
                SettingsToggle(
                    label = "Return to Zero on Release",
                    description = "Pedals snap back to 0 when you lift your finger",
                    checked = settings.pedalReturnOnRelease,
                    onCheckedChange = { settings.pedalReturnOnRelease = it }
                )
                SettingsSlider(
                    label = "Throttle Max",
                    value = settings.throttleMaxByte.toFloat(),
                    min = 128f, max = 255f, step = 1f,
                    formatValue = { "${it.toInt()}" },
                    onValueChange = { settings.throttleMaxByte = it.toInt() }
                )
            }
            
            // GYROSCOPE SECTION
            SettingsSection(
                title = "Gyroscope",
                icon = "GY",
                color = Color(0xFF22D3EE),
                isExpanded = expandedSection == "gyro",
                onToggle = { expandedSection = if (expandedSection == "gyro") null else "gyro" },
                scrollState = scrollState,
                coroutineScope = coroutineScope
            ) {
                SettingsToggle(
                    label = "Enable Gyroscope",
                    description = "Use phone tilt to steer. Best when pedals are above the wheel.",
                    checked = settings.gyroEnabled,
                    onCheckedChange = { settings.gyroEnabled = it }
                )
                SettingsSlider(
                    label = "Max Tilt Angle",
                    value = settings.gyroMaxTiltDeg,
                    min = 10f, max = 45f, step = 1f,
                    formatValue = { "${it.toInt()} deg" },
                    onValueChange = { settings.gyroMaxTiltDeg = it }
                )
                SettingsSlider(
                    label = "Sensitivity",
                    value = settings.gyroSensitivity,
                    min = 0.5f, max = 4.0f, step = 0.1f,
                    formatValue = { "%.1f".format(it) },
                    onValueChange = { settings.gyroSensitivity = it }
                )
                SettingsSlider(
                    label = "Deadzone",
                    value = settings.gyroDeadzoneDeg,
                    min = 0f, max = 8f, step = 0.5f,
                    formatValue = { "%.1f".format(it) + " deg" },
                    onValueChange = { settings.gyroDeadzoneDeg = it }
                )
                SettingsSlider(
                    label = "Filter Strength",
                    value = settings.gyroFilterAlpha,
                    min = 0.05f, max = 0.5f, step = 0.01f,
                    formatValue = { "%.2f".format(it) },
                    onValueChange = { settings.gyroFilterAlpha = it }
                )
                SettingsSlider(
                    label = "Output Smoothing",
                    value = settings.gyroSmoothAlpha,
                    min = 0.05f, max = 0.5f, step = 0.01f,
                    formatValue = { "%.2f".format(it) },
                    onValueChange = { settings.gyroSmoothAlpha = it }
                )
            }
            
            // NETWORK SECTION
            SettingsSection(
                title = "Network",
                icon = "NW",
                color = Color(0xFFF59E0B),
                isExpanded = expandedSection == "network",
                onToggle = { expandedSection = if (expandedSection == "network") null else "network" },
                scrollState = scrollState,
                coroutineScope = coroutineScope
            ) {
                SettingsSlider(
                    label = "Send Rate",
                    value = settings.sendRateHz.toFloat(),
                    min = 60f, max = 480f, step = 30f,
                    formatValue = { "${it.toInt()} Hz" },
                    onValueChange = { settings.sendRateHz = it.toInt() }
                )
                SettingsSlider(
                    label = "Heartbeat Interval",
                    value = settings.heartbeatIntervalMs.toFloat(),
                    min = 500f, max = 3000f, step = 100f,
                    formatValue = { "${it.toInt()}ms" },
                    onValueChange = { settings.heartbeatIntervalMs = it.toInt() }
                )
                SettingsInfo(
                    label = "Discovery Port",
                    value = "19700 (UDP)"
                )
                SettingsInfo(
                    label = "Input Port",
                    value = "19701 (UDP)"
                )
            }
            
            // DISPLAY SECTION
            SettingsSection(
                title = "Display",
                icon = "DP",
                color = Color(0xFF8B5CF6),
                isExpanded = expandedSection == "display",
                onToggle = { expandedSection = if (expandedSection == "display") null else "display" },
                scrollState = scrollState,
                coroutineScope = coroutineScope
            ) {
                SettingsToggle(
                    label = "Show Angle Text",
                    description = "Display current steering angle on the wheel",
                    checked = settings.showAngleText,
                    onCheckedChange = { settings.showAngleText = it }
                )
                SettingsToggle(
                    label = "Show Packet Counter",
                    description = "Show packet count in the status bar",
                    checked = settings.showPacketCounter,
                    onCheckedChange = { settings.showPacketCounter = it }
                )
            }
            
            // BUTTONS SECTION
            SettingsSection(
                title = "Buttons",
                icon = "BT",
                color = Color(0xFFEF4444),
                isExpanded = expandedSection == "buttons",
                onToggle = { expandedSection = if (expandedSection == "buttons") null else "buttons" },
                scrollState = scrollState,
                coroutineScope = coroutineScope
            ) {
                SettingsSlider(
                    label = "Top Row Size",
                    value = settings.buttonSizeTop.toFloat(),
                    min = 36f, max = 60f, step = 2f,
                    formatValue = { "${it.toInt()}dp" },
                    onValueChange = { settings.buttonSizeTop = it.toInt() }
                )
                SettingsSlider(
                    label = "Bottom Row Size",
                    value = settings.buttonSizeBottom.toFloat(),
                    min = 32f, max = 56f, step = 2f,
                    formatValue = { "${it.toInt()}dp" },
                    onValueChange = { settings.buttonSizeBottom = it.toInt() }
                )
                SettingsToggle(
                    label = "Haptic Feedback",
                    description = "Vibrate on button press",
                    checked = settings.hapticFeedback,
                    onCheckedChange = { settings.hapticFeedback = it }
                )
            }
            
// SYSTEM LOGS SECTION
            SettingsSection(
                title = "System Logs",
                icon = "LG",
                color = Color(0xFF3B82F6),
                isExpanded = expandedSection == "logs",
                onToggle = { expandedSection = if (expandedSection == "logs") null else "logs" },
                scrollState = scrollState,
                coroutineScope = coroutineScope
            ) {
                SettingsAction(
                    label = "View Full Screen Logs",
                    description = "Open full screen system logs viewer with copy support",
                    color = Color(0xFF3B82F6),
                    onClick = { onOpenLogs() }
                )
            }

            // DEVELOPER SECTION
            SettingsSection(
                title = "Developer",
                icon = "DV",
                color = Color(0xFF14B8A6),
                isExpanded = expandedSection == "developer",
                onToggle = { expandedSection = if (expandedSection == "developer") null else "developer" },
                scrollState = scrollState,
                coroutineScope = coroutineScope
            ) {
                SettingsAction(
                    label = "Save Current Layout as Default",
                    description = "The current saved HUD layout becomes the default used on reset / first install",
                    color = Color(0xFF14B8A6),
                    onClick = { settings.saveCurrentAsDefaultLayout() }
                )
                SettingsAction(
                    label = "Export Layout (JSON)",
                    description = "Save to Downloads (omniwheel_hud_layout.json) and share the layout JSON with full details: positions, sizes, scale, vJoy mapping",
                    color = Color(0xFF14B8A6),
                    onClick = {
                        settings.saveCurrentAsDefaultLayout()
                        settings.exportLayoutJson(ctx)?.let { path ->
                            exportedPath = path
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_SUBJECT, "OmniWheel HUD Layout")
                                putExtra(Intent.EXTRA_TEXT, settings.loadHudLayout().let { widgetListToJson(it) })
                            }
                            ctx.startActivity(Intent.createChooser(sendIntent, "Share HUD Layout"))
                        } ?: run { exportedPath = "Export failed" }
                    }
                )
                Text(
                    text = exportedPath ?: "",
                    fontSize = 9.sp,
                    color = Color(0xFF12B312)
                )
                SettingsAction(
                    label = "Reset Default Layout",
                    description = "Revert the stored default back to the built-in defaultControllerLayout()",
                    color = Color(0xFFEF4444),
                    onClick = { settings.defaultHudLayoutJson = null }
                )
            }
            
            // DANGER ZONE
            SettingsSection(
                title = "Reset",
                icon = "!!",
                color = Color(0xFFEF4444),
                isExpanded = expandedSection == "reset",
                onToggle = { expandedSection = if (expandedSection == "reset") null else "reset" },
                scrollState = scrollState,
                coroutineScope = coroutineScope
            ) {
                SettingsAction(
                    label = "Reset All Settings to Default",
                    description = "This will reset every setting to its factory default value",
                    color = Color(0xFFEF4444),
                    onClick = { settings.resetToDefaults() }
                )
            }
            
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ==================== REUSABLE SETTINGS COMPONENTS ====================

@Composable
private fun SettingsSection(
    title: String,
    icon: String,
    color: Color,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    scrollState: ScrollState,
    coroutineScope: CoroutineScope,
    content: @Composable ColumnScope.() -> Unit
) {
    var sectionY by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF14142A), MaterialTheme.shapes.medium)
            .onGloballyPositioned { coordinates ->
                sectionY = coordinates.positionInParent().y.toInt()
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onToggle()
                    if (!isExpanded) {
                        coroutineScope.launch {
                            scrollState.animateScrollTo(maxOf(0, sectionY - 12))
                        }
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(color.copy(alpha = 0.15f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = icon,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(Modifier.width(12.dp))
            
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE0E0E0),
                modifier = Modifier.weight(1f)
            )
            
            Text(
                text = if (isExpanded) "v" else ">",
                fontSize = 14.sp,
                color = Color(0xFF555555),
                fontWeight = FontWeight.Bold
            )
        }
        
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(150))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .background(Color(0xFF0F0F22))
            ) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    formatValue: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    var sliderValue by remember { mutableStateOf(value) }

    // Keep slider in sync if the parent passes a new value
    LaunchedEffect(value) { sliderValue = value }

    // Derive display from the live slider value
    val displayText = formatValue(sliderValue)
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = Color(0xFFBBBBBB)
            )
            Text(
                text = displayText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF6366F1),
                textAlign = TextAlign.End
            )
        }
        
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                val stepped = (it / step).roundToInt() * step
                sliderValue = stepped.coerceIn(min, max)
                onValueChange(stepped)
            },
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF6366F1),
                activeTrackColor = Color(0xFF6366F1),
                inactiveTrackColor = Color(0xFF2A2A44)
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
    }
}

@Composable
private fun SettingsToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    // Local state so the toggle updates visually immediately
    var localChecked by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) { localChecked = checked }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = Color(0xFFBBBBBB)
            )
            Text(
                text = description,
                fontSize = 9.sp,
                color = Color(0xFF666666)
            )
        }
        Switch(
            checked = localChecked,
            onCheckedChange = {
                localChecked = it
                onCheckedChange(it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF6366F1),
                checkedTrackColor = Color(0xFF6366F1).copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun SettingsInfo(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFFBBBBBB)
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = Color(0xFF666666),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SettingsAction(
    label: String,
    description: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.clickable { onClick() }
        )
        Text(
            text = description,
            fontSize = 9.sp,
            color = Color(0xFF666666)
        )
    }
}

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()