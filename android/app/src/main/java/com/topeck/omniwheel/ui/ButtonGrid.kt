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
            fontSize = if (size <= 44) 13.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive || isPressed) Color.White else Color(0xFF6A6A8A),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Wide button (for buttons like 1, 2, 3, 13 that are wider in the reference).
 */
@Composable
fun WideButtonView(
    button: VButton,
    isActive: Boolean,
    onPressed: (Int, Boolean) -> Unit,
    widthDp: Int = 80,
    heightDp: Int = 48,
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
            .width(widthDp.dp)
            .height(heightDp.dp)
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
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive || isPressed) Color.White else Color(0xFF6A6A8A),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Button that fills its HUD slot exactly (used by the game screen so the
 * rendered size matches the HUD editor's rectangle to the pixel).
 */
@Composable
fun HudButtonView(
    button: VButton,
    isActive: Boolean,
    onPressed: (Int, Boolean) -> Unit,
    hapticEnabled: Boolean = true
) {
    var isPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val bgColor = when {
        isActive -> button.color.copy(alpha = 0.85f)
        isPressed -> button.color.copy(alpha = 0.45f)
        else -> Color(0xFF16162E)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor, RoundedCornerShape(8.dp))
            .drawBehind {
                val borderColor = when {
                    isActive -> button.color.copy(alpha = 0.6f)
                    else -> Color(0xFF2A2A44)
                }
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(8.dp.toPx()),
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
        val font = when {
            maxHeight < 22.dp -> 9.sp
            maxHeight < 30.dp -> 11.sp
            maxHeight < 40.dp -> 13.sp
            else -> 15.sp
        }
        Text(
            text = button.label,
            fontSize = font,
            fontWeight = FontWeight.Bold,
            color = if (isActive || isPressed) Color.White else Color(0xFF6A6A8A),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * All 18 buttons matching the reference layout.
 */
fun allButtons(): Map<Int, VButton> = mapOf(
    1 to VButton(1, "1", Color(0xFF3A3A5E), action = 1),
    2 to VButton(2, "2", Color(0xFF3A3A5E), action = 2),
    3 to VButton(3, "3", Color(0xFF3A3A5E), action = 3),
    4 to VButton(4, "4", Color(0xFF3A3A5E), action = 4),
    5 to VButton(5, "5", Color(0xFF3A3A5E), action = 5),
    6 to VButton(6, "6", Color(0xFF3A3A5E), action = 6),
    7 to VButton(7, "7", Color(0xFF3A3A5E), action = 7),
    8 to VButton(8, "8", Color(0xFF3A3A5E), action = 8),
    9 to VButton(9, "9", Color(0xFF3A3A5E), action = 9),
    10 to VButton(10, "10", Color(0xFF3A3A5E), action = 10),
    11 to VButton(11, "11", Color(0xFF3A3A5E), action = 11),
    12 to VButton(12, "12", Color(0xFF3A3A5E), action = 12),
    13 to VButton(13, "13", Color(0xFF3A3A5E), action = 13),
    14 to VButton(14, "14", Color(0xFF3A3A5E), action = 14),
    15 to VButton(15, "15", Color(0xFF3A3A5E), action = 15),
    17 to VButton(17, "17", Color(0xFF3A3A5E), action = 17),
    18 to VButton(18, "18", Color(0xFF3A3A5E), action = 18),
)

/**
 * Compact status bar — connection info only.
 */
@Composable
fun ControllerStatusBar(
    gyroAvailable: Boolean,
    gyroActive: Boolean,
    connectedIp: String,
    packetCount: Int,
    layoutSyncInfo: String,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "●",
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
        
        Text(
            text = layoutSyncInfo,
            fontSize = 8.sp,
            color = Color(0xFF22C55E),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "v0.9.12",
                fontSize = 8.sp,
                color = Color(0xFF555555)
            )
            Spacer(Modifier.width(6.dp))
            if (gyroAvailable) {
                Text(
                    text = if (gyroActive) "GYRO ON" else "GYRO OFF",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (gyroActive) Color(0xFF22D3EE) else Color(0xFF333333)
                )
            }
        }
    }
}