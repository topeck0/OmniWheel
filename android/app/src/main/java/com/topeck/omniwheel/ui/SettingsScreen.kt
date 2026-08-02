package com.topeck.omniwheel.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.R
import com.topeck.omniwheel.SettingsManager

/**
 * Settings screen with organized sections (folders).
 * Each section is collapsible and contains related settings.
 */
@Composable
fun SettingsScreen(
    settings: SettingsManager,
    onBack: () -> Unit
) {
    var expandedSection by remember { mutableStateOf<String?>("steering") }
    
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
                text = "v0.6.1",
                fontSize = 10.sp,
                color = Color(0xFF444444)
            )
        }
        
        // Scrollable settings sections
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // STEERING SECTION
            SettingsSection(
                title = "Steering",
                icon = "SR",
                color = Color(0xFF6366F1),
                isExpanded = expandedSection == "steering",
                onToggle = { expandedSection = if (expandedSection == "steering") null else "steering" }
            ) {
                SettingsSlider(
                    label = "Max Angle",
                    value = settings.steeringMaxAngle.toFloat(),
                    min = 180f, max = 1800f, step = 10f,
                    displayValue = "${settings.steeringMaxAngle} deg",
                    onValueChange = { settings.steeringMaxAngle = it.toInt() }
                )
                SettingsSlider(
                    label = "Sensitivity",
                    value = settings.steeringSensitivity,
                    min = 0.2f, max = 3.0f, step = 0.05f,
                    displayValue = "${"%.2f".format(settings.steeringSensitivity)}",
                    onValueChange = { settings.steeringSensitivity = it }
                )
                SettingsSlider(
                    label = "Deadzone",
                    value = settings.steeringDeadzone,
                    min = 0f, max = 0.1f, step = 0.005f,
                    displayValue = "${"%.3f".format(settings.steeringDeadzone)}",
                    onValueChange = { settings.steeringDeadzone = it }
                )
                SettingsSlider(
                    label = "Smoothing",
                    value = settings.steeringSmoothing,
                    min = 0f, max = 0.3f, step = 0.01f,
                    displayValue = "${"%.2f".format(settings.steeringSmoothing)}",
                    onValueChange = { settings.steeringSmoothing = it }
                )
                SettingsSlider(
                    label = "Spring Return Speed",
                    value = settings.springStrength,
                    min = 1f, max = 15f, step = 0.5f,
                    displayValue = "${"%.1f".format(settings.springStrength)}",
                    onValueChange = { settings.springStrength = it }
                )
                SettingsSlider(
                    label = "Damping",
                    value = settings.springDamping,
                    min = 1f, max = 10f, step = 0.5f,
                    displayValue = "${"%.1f".format(settings.springDamping)}",
                    onValueChange = { settings.springDamping = it }
                )
                SettingsSlider(
                    label = "Touch Drag Zone",
                    value = settings.touchDragZone,
                    min = 0.2f, max = 0.8f, step = 0.05f,
                    displayValue = "${"%.2f".format(settings.touchDragZone)}",
                    onValueChange = { settings.touchDragZone = it }
                )
            }
            
            // PEDALS SECTION
            SettingsSection(
                title = "Pedals",
                icon = "PD",
                color = Color(0xFF22C55E),
                isExpanded = expandedSection == "pedals",
                onToggle = { expandedSection = if (expandedSection == "pedals") null else "pedals" }
            ) {
                SettingsSlider(
                    label = "Pedal Width",
                    value = settings.pedalWidth.toFloat(),
                    min = 50f, max = 90f, step = 2f,
                    displayValue = "${settings.pedalWidth}dp",
                    onValueChange = { settings.pedalWidth = it.toInt() }
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
                    displayValue = "${settings.throttleMaxByte}",
                    onValueChange = { settings.throttleMaxByte = it.toInt() }
                )
            }
            
            // GYROSCOPE SECTION
            SettingsSection(
                title = "Gyroscope",
                icon = "GY",
                color = Color(0xFF22D3EE),
                isExpanded = expandedSection == "gyro",
                onToggle = { expandedSection = if (expandedSection == "gyro") null else "gyro" }
            ) {
                SettingsSlider(
                    label = "Max Tilt Angle",
                    value = settings.gyroMaxTiltDeg,
                    min = 10f, max = 45f, step = 1f,
                    displayValue = "${settings.gyroMaxTiltDeg.toInt()} deg",
                    onValueChange = { settings.gyroMaxTiltDeg = it }
                )
                SettingsSlider(
                    label = "Sensitivity",
                    value = settings.gyroSensitivity,
                    min = 0.5f, max = 4.0f, step = 0.1f,
                    displayValue = "${"%.1f".format(settings.gyroSensitivity)}",
                    onValueChange = { settings.gyroSensitivity = it }
                )
                SettingsSlider(
                    label = "Deadzone",
                    value = settings.gyroDeadzoneDeg,
                    min = 0f, max = 8f, step = 0.5f,
                    displayValue = "${"%.1f".format(settings.gyroDeadzoneDeg)} deg",
                    onValueChange = { settings.gyroDeadzoneDeg = it }
                )
                SettingsSlider(
                    label = "Filter Strength",
                    value = settings.gyroFilterAlpha,
                    min = 0.05f, max = 0.5f, step = 0.01f,
                    displayValue = "${"%.2f".format(settings.gyroFilterAlpha)}",
                    onValueChange = { settings.gyroFilterAlpha = it }
                )
                SettingsSlider(
                    label = "Output Smoothing",
                    value = settings.gyroSmoothAlpha,
                    min = 0.05f, max = 0.5f, step = 0.01f,
                    displayValue = "${"%.2f".format(settings.gyroSmoothAlpha)}",
                    onValueChange = { settings.gyroSmoothAlpha = it }
                )
            }
            
            // NETWORK SECTION
            SettingsSection(
                title = "Network",
                icon = "NW",
                color = Color(0xFFF59E0B),
                isExpanded = expandedSection == "network",
                onToggle = { expandedSection = if (expandedSection == "network") null else "network" }
            ) {
                SettingsSlider(
                    label = "Send Rate",
                    value = settings.sendRateHz.toFloat(),
                    min = 60f, max = 480f, step = 30f,
                    displayValue = "${settings.sendRateHz} Hz",
                    onValueChange = { settings.sendRateHz = it.toInt() }
                )
                SettingsSlider(
                    label = "Heartbeat Interval",
                    value = settings.heartbeatIntervalMs.toFloat(),
                    min = 500f, max = 3000f, step = 100f,
                    displayValue = "${settings.heartbeatIntervalMs}ms",
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
                onToggle = { expandedSection = if (expandedSection == "display") null else "display" }
            ) {
                SettingsToggle(
                    label = "Show Angle Text",
                    description = "Display current steering angle on the wheel",
                    checked = settings.showAngleText,
                    onCheckedChange = { settings.showAngleText = it }
                )
                SettingsToggle(
                    label = "Show Mode Indicator",
                    description = "Show TOUCH/GYRO mode label on the wheel",
                    checked = settings.showModeIndicator,
                    onCheckedChange = { settings.showModeIndicator = it }
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
                onToggle = { expandedSection = if (expandedSection == "buttons") null else "buttons" }
            ) {
                SettingsSlider(
                    label = "Top Row Size",
                    value = settings.buttonSizeTop.toFloat(),
                    min = 36f, max = 60f, step = 2f,
                    displayValue = "${settings.buttonSizeTop}dp",
                    onValueChange = { settings.buttonSizeTop = it.toInt() }
                )
                SettingsSlider(
                    label = "Bottom Row Size",
                    value = settings.buttonSizeBottom.toFloat(),
                    min = 32f, max = 56f, step = 2f,
                    displayValue = "${settings.buttonSizeBottom}dp",
                    onValueChange = { settings.buttonSizeBottom = it.toInt() }
                )
                SettingsToggle(
                    label = "Haptic Feedback",
                    description = "Vibrate on button press",
                    checked = settings.hapticFeedback,
                    onCheckedChange = { settings.hapticFeedback = it }
                )
            }
            
            // DANGER ZONE
            SettingsSection(
                title = "Reset",
                icon = "!!",
                color = Color(0xFFEF4444),
                isExpanded = expandedSection == "reset",
                onToggle = { expandedSection = if (expandedSection == "reset") null else "reset" }
            ) {
                SettingsAction(
                    label = "Reset All Settings to Default",
                    description = "This will reset every setting to its factory default value",
                    color = Color(0xFFEF4444),
                    onClick = { settings.resetToDefaults() }
                )
            }
            
            // Bottom spacing
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
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF14142A), MaterialTheme.shapes.medium)
    ) {
        // Section header (clickable to expand/collapse)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon badge
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
        
        // Expanded content
        if (isExpanded) {
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
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableStateOf(value) }
    
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
                text = displayValue,
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
                // Snap to step
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
            checked = checked,
            onCheckedChange = onCheckedChange,
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
