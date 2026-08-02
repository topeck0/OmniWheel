package com.topeck.omniwheel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Configurable virtual button.
 */
data class VButton(
    val id: Int,
    val label: String,
    val color: Color = Color(0xFF3A3A5E),
    val mode: ButtonMode = ButtonMode.TAP,
    val action: Int = 0
)

enum class ButtonMode { TAP, HOLD, TOGGLE }

@Composable
fun VButtonView(
    button: VButton,
    isActive: Boolean,
    onPressed: (Int, Boolean) -> Unit,
    size: Int = 52,
    hapticEnabled: Boolean = true
) {
    var isPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val bgColor = when {
        isActive -> button.color.copy(alpha = 0.85f)
        isPressed -> button.color.copy(alpha = 0.45f)
        else -> Color(0xFF16162E)
    }

    Box(
        modifier = Modifier
            .size(size.dp)
            .background(bgColor, RoundedCornerShape(10.dp))
            .drawBehind {
                val borderColor = when {
                    isActive -> button.color.copy(alpha = 0.6f)
                    else -> Color(0xFF2A2A44)
                }
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                )
            }
            .pointerInput(button.action) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPressed(button.action, true)
                        tryAwaitRelease()
                        isPressed = false
                        if (button.mode != ButtonMode.TOGGLE) {
                            onPressed(button.action, false)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = button.label,
            fontSize = if (size <= 44) 9.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive || isPressed) Color.White else Color(0xFF6A6A8A),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Default button layout for racing games.
 * Top row: A B X Y LB RB (face buttons + bumpers)
 * Bottom row: UP DN LT RT L R (d-pad + triggers + sticks)
 */
fun defaultButtons(): List<VButton> = listOf(
    VButton(1,  "A",   Color(0xFF22C55E), action = 1),
    VButton(2,  "B",   Color(0xFFEF4444), action = 2),
    VButton(3,  "X",   Color(0xFF3B82F6), action = 3),
    VButton(4,  "Y",   Color(0xFFF59E0B), action = 4),
    VButton(5,  "LB",  Color(0xFF6366F1), action = 5),
    VButton(6,  "RB",  Color(0xFF6366F1), action = 6),
    VButton(7,  "UP",  Color(0xFF475569), action = 7),
    VButton(8,  "DN",  Color(0xFF475569), action = 8),
    VButton(9,  "LT",  Color(0xFF8B5CF6), action = 9),
    VButton(10, "RT",  Color(0xFF8B5CF6), action = 10),
    VButton(11, "L",   Color(0xFF475569), action = 11),
    VButton(12, "R",   Color(0xFF475569), action = 12),
)

/**
 * Single row of buttons.
 */
@Composable
fun ButtonRow(
    buttons: List<VButton>,
    activeButtons: Set<Int>,
    onButtonPress: (Int, Boolean) -> Unit,
    buttonSize: Int = 52,
    hapticEnabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        buttons.forEach { btn ->
            VButtonView(
                button = btn,
                isActive = activeButtons.contains(btn.action),
                onPressed = onButtonPress,
                size = buttonSize,
                hapticEnabled = hapticEnabled
            )
        }
    }
}

/**
 * Compact status bar for the controller screen.
 */
@Composable
fun ControllerStatusBar(
    gyroEnabled: Boolean,
    gyroAvailable: Boolean,
    connectedIp: String,
    packetCount: Int,
    onGyroToggle: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(Color(0xFF080812))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: connection status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\u25CF",
                fontSize = 8.sp,
                color = Color(0xFF22C55E)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = connectedIp,
                fontSize = 8.sp,
                color = Color(0xFF444444)
            )
            if (packetCount >= 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${packetCount}pk",
                    fontSize = 8.sp,
                    color = Color(0xFF333333)
                )
            }
        }
        
        // Center: version
        Text(
            text = "v0.6.1",
            fontSize = 8.sp,
            color = Color(0xFF333333)
        )
        
        // Right side buttons
        Row(verticalAlignment = Alignment.CenterVertically) {
            // GYRO toggle
            if (gyroAvailable) {
                Box(
                    modifier = Modifier
                        .background(
                            if (gyroEnabled) Color(0xFF6366F1) else Color(0xFF1E1E36),
                            RoundedCornerShape(5.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 1.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { onGyroToggle() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "GYRO",
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (gyroEnabled) Color.White else Color(0xFF555555)
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            
            // EXIT
            Box(
                modifier = Modifier
                    .background(Color(0xFF2A1A1A), RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { onDisconnect() }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "EXIT",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF4444)
                )
            }
        }
    }
}