package com.topeck.omniwheel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.R
import kotlinx.coroutines.delay
import kotlin.math.*

/**
 * Image-based steering wheel with rotation input.
 * Uses a high-quality PNG icon that rotates with touch, like a real wheel.
 */
@Composable
fun SteeringWheelView(
    viewModel: SteeringViewModel,
    isGyroActive: Boolean = false,
    showAngleText: Boolean = true,
    modifier: Modifier = Modifier
) {
    val angle by viewModel.displayAngle
    val rotDeg by viewModel.visualRotationDeg
    val isTouching = remember { mutableStateOf(false) }

    // Physics loop for spring return when not touching (and gyro is NOT active)
    LaunchedEffect(isGyroActive) {
        while (true) {
            if (!isGyroActive) {
                viewModel.updatePhysics()
            }
            delay(1000L / 240)
        }
    }

    val activeColor = if (isGyroActive) Color(0xFF22D3EE) else Color(0xFF6366F1)
    val engaged = isTouching.value || isGyroActive

    Box(
        modifier = modifier
            .fillMaxHeight()
            .aspectRatio(2.0f)
            .clipToBounds()
            .let { m ->
                if (isGyroActive) m
                else m.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isTouching.value = true
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            viewModel.onRotationTouchStart(offset.x - cx, offset.y - cy)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            viewModel.onRotationTouchMove(change.position.x - cx, change.position.y - cy)
                        },
                        onDragEnd = {
                            isTouching.value = false
                            viewModel.onTouchEnd()
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Glow circle behind wheel when active
        if (engaged) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = minOf(cx, cy) * 0.7f
                drawCircle(
                    activeColor.copy(alpha = 0.08f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(20f)
                )
            }
        }

        // Steering wheel image with hardware-accelerated rotation
        Image(
            painter = painterResource(R.drawable.steering_wheel),
            contentDescription = "Steering Wheel",
            modifier = Modifier
                .fillMaxSize(0.85f)
                .graphicsLayer {
                    rotationZ = rotDeg
                }
        )

        // Angle text overlay at bottom of wheel
        if (showAngleText) {
            val deg = (angle * 900f).toInt()
            val textColor = when {
                isGyroActive -> Color(0xFF22D3EE)
                isTouching.value -> Color.White
                else -> Color(0xFF555555)
            }
            Text(
                text = "${deg}°",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}
