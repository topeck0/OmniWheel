package com.topeck.omniwheel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared static look of a pedal (label + rounded track + fill + value).
 * Used by BOTH the game screen (inside PedalView) and the HUD editor so the
 * pedals render identical in shape, size and curve.
 */
@Composable
fun PedalShape(
    label: String,
    color: Color,
    value: Float,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    trackModifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val displayValue = remember { mutableStateOf(0f) }
    LaunchedEffect(value) {
        displayValue.value = value
    }
    val fill = displayValue.value.coerceIn(0f, 1f)

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Track fills the whole widget so there is never a hollow/empty
        // outlined region above the pedal.
        var trackMod = Modifier
            .fillMaxWidth(0.9f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF12122A))
        if (selected) {
            trackMod = trackMod
                .border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
        }
        trackMod = trackMod.then(trackModifier)
        Box(
            modifier = trackMod,
            contentAlignment = Alignment.BottomCenter
        ) {
            if (fill > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fill)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    color.copy(alpha = if (pressed) 0.5f else 0.25f),
                                    if (pressed) color else color.copy(alpha = 0.7f)
                                )
                            )
                        )
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineSpacingPx = 12f
                val lineCount = (size.height / lineSpacingPx).toInt()
                for (i in 1 until lineCount) {
                    val y = size.height - i * lineSpacingPx
                    drawLine(
                        color = Color.White.copy(alpha = 0.045f),
                        start = Offset(size.width * 0.15f, y),
                        end = Offset(size.width * 0.85f, y),
                        strokeWidth = 1f
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (pressed) color else Color(0xFF555555),
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.sp
                )
            }

            Text(
                text = "${(fill * 100).toInt()}",
                fontSize = if (fill > 0.01f) 16.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (fill > 0.01f) Color.White else Color(0xFF444444),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 34.dp)
            )
        }
    }
}

@Composable
fun PedalView(
    label: String,
    color: Color,
    value: Float,
    onValueChange: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPressed = remember { mutableStateOf(false) }

    PedalShape(
        label = label,
        color = color,
        value = value,
        modifier = modifier,
        pressed = isPressed.value,
        trackModifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                isPressed.value = true
                val relY = 1f - (down.position.y / size.height).coerceIn(0f, 1f)
                onValueChange(relY)

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.first()
                    if (!change.pressed) break
                    change.consume()
                    val newY = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    onValueChange(newY)
                }

                isPressed.value = false
                onRelease()
            }
        }
    )
}

/**
 * Vertical pedals column on the far right side.
 * Gas on right, Brake on left, optional Clutch on far left.
 */
@Composable
fun VerticalPedalsColumn(
    throttle: Float,
    brake: Float,
    clutch: Float,
    clutchEnabled: Boolean,
    onThrottleChange: (Float) -> Unit,
    onBrakeChange: (Float) -> Unit,
    onClutchChange: (Float) -> Unit,
    onThrottleRelease: () -> Unit,
    onBrakeRelease: () -> Unit,
    onClutchRelease: () -> Unit,
    pedalWidthDp: Int = 68
) {
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom
    ) {
        if (clutchEnabled) {
            PedalView(
                label = "CLUTCH",
                color = Color(0xFFF59E0B),
                value = clutch,
                onValueChange = onClutchChange,
                onRelease = onClutchRelease
            )
        }
        PedalView(
            label = "BRAKE",
            color = Color(0xFFEF4444),
            value = brake,
            onValueChange = onBrakeChange,
            onRelease = onBrakeRelease
        )
        PedalView(
            label = "GAS",
            color = Color(0xFF22C55E),
            value = throttle,
            onValueChange = onThrottleChange,
            onRelease = onThrottleRelease
        )
    }
}