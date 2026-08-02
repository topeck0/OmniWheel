package com.topeck.omniwheel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
 * Horizontal pedal slider — designed to sit ABOVE the steering wheel.
 * Drag left-to-right to increase value.
 */
@Composable
fun HorizontalPedalView(
    label: String,
    color: Color,
    value: Float,
    onValueChange: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayValue = remember { mutableStateOf(0f) }
    val isPressed = remember { mutableStateOf(false) }
    
    LaunchedEffect(value) {
        displayValue.value += (value - displayValue.value) * 0.35f
        if (value <= 0f) displayValue.value = 0f
    }
    
    Row(
        modifier = modifier.height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Label
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPressed.value) color else Color(0xFF555555),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp),
            letterSpacing = 0.5.sp
        )
        
        Spacer(Modifier.width(4.dp))
        
        // Horizontal slider track
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(0.65f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF12122A))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isPressed.value = true
                            val relX = (it.x / size.width).coerceIn(0f, 1f)
                            onValueChange(relX)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val relX = (change.position.x / size.width).coerceIn(0f, 1f)
                            onValueChange(relX)
                        },
                        onDragEnd = {
                            isPressed.value = false
                            onRelease()
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // Fill from left
            val fillWidth = displayValue.value.coerceIn(0f, 1f)
            if (fillWidth > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fillWidth)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    if (isPressed.value) color else color.copy(alpha = 0.7f),
                                    color.copy(alpha = if (isPressed.value) 0.5f else 0.25f)
                                )
                            )
                        )
                )
            }
            
            // Grip lines
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineSpacingPx = 14f
                val lineCount = (size.width / lineSpacingPx).toInt()
                for (i in 1 until lineCount) {
                    val x = i * lineSpacingPx
                    drawLine(
                        color = Color.White.copy(alpha = 0.04f),
                        start = Offset(x, size.height * 0.15f),
                        end = Offset(x, size.height * 0.85f),
                        strokeWidth = 1f
                    )
                }
            }
            
            // Value text
            Text(
                text = "${(displayValue.value * 100).toInt()}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (displayValue.value > 0.01f) Color.White else Color(0xFF444444)
            )
        }
    }
}

/**
 * Horizontal pedals row: Gas + Brake (and optional Clutch).
 * Designed to sit above the steering wheel.
 */
@Composable
fun HorizontalPedalsRow(
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (clutchEnabled) {
            HorizontalPedalView(
                label = "CLUTCH",
                color = Color(0xFFF59E0B),
                value = clutch,
                onValueChange = onClutchChange,
                onRelease = onClutchRelease,
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalPedalView(
            label = "BRAKE",
            color = Color(0xFFEF4444),
            value = brake,
            onValueChange = onBrakeChange,
            onRelease = onBrakeRelease,
            modifier = Modifier.weight(1f)
        )
        HorizontalPedalView(
            label = "GAS",
            color = Color(0xFF22C55E),
            value = throttle,
            onValueChange = onThrottleChange,
            onRelease = onThrottleRelease,
            modifier = Modifier.weight(1f)
        )
    }
}