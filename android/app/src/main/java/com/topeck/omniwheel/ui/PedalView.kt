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

@Composable
fun PedalView(
    label: String,
    color: Color,
    value: Float,  // 0..1
    onValueChange: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayValue = remember { mutableStateOf(0f) }
    val isPressed = remember { mutableStateOf(false) }
    
    // Smooth the display value for visual only (input itself is instant)
    LaunchedEffect(value) {
        displayValue.value += (value - displayValue.value) * 0.35f
        if (value <= 0f) displayValue.value = 0f
    }
    
    Column(
        modifier = modifier
            .fillMaxHeight(0.88f)
            .width(68.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        // Label
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPressed.value) color else Color(0xFF555555),
            textAlign = TextAlign.Center,
            letterSpacing = 1.sp
        )
        
        Spacer(Modifier.height(4.dp))
        
        // Pedal track
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF12122A))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isPressed.value = true
                            val relY = 1f - (it.y / size.height).coerceIn(0f, 1f)
                            onValueChange(relY)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val relY = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            onValueChange(relY)
                        },
                        onDragEnd = {
                            isPressed.value = false
                            onRelease()
                        }
                    )
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            // Fill from bottom with gradient
            val fillHeight = displayValue.value.coerceIn(0f, 1f)
            if (fillHeight > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fillHeight)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    color.copy(alpha = if (isPressed.value) 0.5f else 0.25f),
                                    if (isPressed.value) color else color.copy(alpha = 0.7f)
                                )
                            )
                        )
                )
            }
            
            // Grip lines (subtle texture)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineSpacingPx = 12f
                val lineCount = (size.height / lineSpacingPx).toInt()
                for (i in 1 until lineCount) {
                    val y = size.height - i * lineSpacingPx
                    drawLine(
                        color = Color.White.copy(alpha = 0.04f),
                        start = Offset(size.width * 0.15f, y),
                        end = Offset(size.width * 0.85f, y),
                        strokeWidth = 1f
                    )
                }
            }
            
            // Value text
            Text(
                text = "${(displayValue.value * 100).toInt()}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (displayValue.value > 0.01f) Color.White else Color(0xFF444444),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 20.dp)
            )
        }
    }
}

@Composable
fun PedalsRow(
    throttle: Float,
    brake: Float,
    clutch: Float,
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
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom
    ) {
        // Clutch (left)
        PedalView(
            label = "CLUTCH",
            color = Color(0xFFF59E0B),
            value = clutch,
            onValueChange = onClutchChange,
            onRelease = onClutchRelease
        )
        // Brake (middle)
        PedalView(
            label = "BRAKE",
            color = Color(0xFFEF4444),
            value = brake,
            onValueChange = onBrakeChange,
            onRelease = onBrakeRelease
        )
        // Throttle (right)
        PedalView(
            label = "GAS",
            color = Color(0xFF22C55E),
            value = throttle,
            onValueChange = onThrottleChange,
            onRelease = onThrottleRelease
        )
    }
}