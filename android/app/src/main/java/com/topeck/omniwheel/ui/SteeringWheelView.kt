package com.topeck.omniwheel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

/**
 * Realistic flat-bottom racing steering wheel with ROTATION-based input.
 * Touch anywhere on the wheel and rotate like a real steering wheel.
 */
@Composable
fun SteeringWheelView(
    viewModel: SteeringViewModel,
    isGyroActive: Boolean = false,
    showAngleText: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Read Compose State directly — triggers recomposition when ViewModel updates it
    val angle by viewModel.displayAngle
    val isTouching = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.updatePhysics()
            delay(1000L / 240)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .aspectRatio(2.0f)
            .pointerInput(Unit) {
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
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val baseRadius = minOf(cx, cy) * 0.78f
            val rimW = baseRadius * 0.22f
            val hubR = baseRadius * 0.24f
            val rotDeg = angle * 180f

            val activeColor = if (isGyroActive) Color(0xFF22D3EE) else Color(0xFF6366F1)
            val engaged = isTouching.value || isGyroActive

            // 1. Shadow
            drawCircle(Color(0x25000000), radius = baseRadius + 5f,
                center = Offset(cx + 3f, cy + 3f))

            // 2. Glow when active
            if (engaged) {
                drawCircle(activeColor.copy(alpha = 0.1f), radius = baseRadius + rimW,
                    center = center, style = Stroke(16f))
            }

            // 3. D-SHAPED RIM
            drawDRim(cx, cy, baseRadius, rimW, rotDeg, engaged, activeColor)

            // 4. TAPERED 3-SPOKES
            drawTaperedSpokes(cx, cy, baseRadius, rimW, hubR, rotDeg)

            // 5. CENTER HUB
            drawCenterHub(cx, cy, hubR, engaged, activeColor)

            // 6. 12 O'CLOCK YELLOW MARKER
            drawTopMarker(cx, cy, baseRadius, rimW, rotDeg)

            // 7. TICK MARKS outside rim
            drawTicks(cx, cy, baseRadius, rimW, rotDeg, activeColor)

            // 8. ANGLE TEXT
            if (showAngleText) {
                drawAngleDisplay(cx, cy, hubR, baseRadius, angle, isGyroActive, isTouching.value, activeColor)
            }
        }
    }
}

// ===== D-SHAPED RIM =====
private fun DrawScope.drawDRim(
    cx: Float, cy: Float, r: Float, w: Float,
    rotDeg: Float, engaged: Boolean, accent: Color
) {
    val flatHalfDeg = 22f
    val flatStart = 90f - flatHalfDeg + rotDeg
    val flatEnd = 90f + flatHalfDeg + rotDeg
    val arcSpan = 360f - (flatEnd - flatStart)

    fun makePath(radius: Float): Path {
        return Path().apply {
            val startRad = (flatEnd - 90f) * PI.toFloat() / 180f
            val sx = cx + cos(startRad) * radius
            val sy = cy + sin(startRad) * radius
            moveTo(sx, sy)

            val segments = 80
            for (i in 1..segments) {
                val t = i.toFloat() / segments
                val angleDeg = flatEnd + t * arcSpan
                val angleRad = (angleDeg - 90f) * PI.toFloat() / 180f
                lineTo(
                    cx + cos(angleRad) * radius,
                    cy + sin(angleRad) * radius
                )
            }

            val endRad = (flatStart - 90f) * PI.toFloat() / 180f
            lineTo(cx + cos(endRad) * radius, cy + sin(endRad) * radius)
            close()
        }
    }

    drawPath(makePath(r), color = Color(0xFF181828),
        style = Stroke(w + 4f, cap = StrokeCap.Butt))

    val leatherColor = if (engaged) Color(0xFF2A2A48) else Color(0xFF222240)
    drawPath(makePath(r), color = leatherColor,
        style = Stroke(w, cap = StrokeCap.Butt))

    val edgeCol = if (engaged) accent.copy(alpha = 0.4f) else Color(0xFF3A3A5A)
    drawPath(makePath(r + 0.5f), color = edgeCol,
        style = Stroke(1.5f, cap = StrokeCap.Butt))

    drawPath(makePath(r - w * 0.3f), color = Color(0xFF2E2E4A).copy(alpha = 0.4f),
        style = Stroke(1f, cap = StrokeCap.Butt))

    val dotPath = makePath(r)
    val dotCount = 60
    for (i in 0 until dotCount) {
        val t = i.toFloat() / dotCount
        val angleDeg = flatEnd + t * arcSpan
        val norm = ((angleDeg - rotDeg) % 360 + 360) % 360
        if (norm < 20f || norm > 340f) continue
        val angleRad = (angleDeg - 90f) * PI.toFloat() / 180f
        drawCircle(
            Color.White.copy(alpha = 0.03f), 1f,
            center = Offset(cx + cos(angleRad) * r, cy + sin(angleRad) * r)
        )
    }

    for (flatAngle in listOf(flatStart, flatEnd)) {
        val rad = (flatAngle - 90f) * PI.toFloat() / 180f
        val x1 = cx + cos(rad) * (r - w / 2f - 1f)
        val y1 = cy + sin(rad) * (r - w / 2f - 1f)
        val x2 = cx + cos(rad) * (r + w / 2f + 1f)
        val y2 = cy + sin(rad) * (r + w / 2f + 1f)
        drawLine(Color(0xFF3A3A5A), Offset(x1, y1), Offset(x2, y2), 1.5f)
    }
}

// ===== TAPERED SPOKES =====
private fun DrawScope.drawTaperedSpokes(
    cx: Float, cy: Float, r: Float, rimW: Float,
    hubR: Float, rotDeg: Float
) {
    val spokeAngles = listOf(-90f, 30f, 150f)
    val innerR = hubR + 3f
    val outerR = r - rimW / 2f - 2f

    for (baseDeg in spokeAngles) {
        val rad = (baseDeg + rotDeg) * PI.toFloat() / 180f
        val hubW = rimW * 0.65f
        val rimWid = rimW * 0.28f

        val cosA = cos(rad); val sinA = sin(rad)
        val pCos = cos(rad + PI.toFloat() / 2f)
        val pSin = sin(rad + PI.toFloat() / 2f)

        val p1 = Offset(cx + cosA * innerR + pCos * hubW / 2, cy + sinA * innerR + pSin * hubW / 2)
        val p2 = Offset(cx + cosA * innerR - pCos * hubW / 2, cy + sinA * innerR - pSin * hubW / 2)
        val p3 = Offset(cx + cosA * outerR - pCos * rimWid / 2, cy + sinA * outerR - pSin * rimWid / 2)
        val p4 = Offset(cx + cosA * outerR + pCos * rimWid / 2, cy + sinA * outerR + pSin * rimWid / 2)

        drawPath(Path().apply {
            moveTo(p1.x + 2f, p1.y + 2f); lineTo(p2.x + 2f, p2.y + 2f)
            lineTo(p3.x + 2f, p3.y + 2f); lineTo(p4.x + 2f, p4.y + 2f)
            close()
        }, color = Color(0xFF08081A))

        drawPath(Path().apply {
            moveTo(p1.x, p1.y); lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y); lineTo(p4.x, p4.y)
            close()
        }, color = Color(0xFF1C1C36))

        drawPath(Path().apply { moveTo(p1.x, p1.y); lineTo(p4.x, p4.y) },
            color = Color(0xFF2A2A4C), style = Stroke(1f))

        drawPath(Path().apply { moveTo(p2.x, p2.y); lineTo(p3.x, p3.y) },
            color = Color(0xFF0E0E20), style = Stroke(1f))
    }
}

// ===== CENTER HUB =====
private fun DrawScope.drawCenterHub(
    cx: Float, cy: Float, hubR: Float, engaged: Boolean, accent: Color
) {
    drawCircle(Color(0xFF0A0A18), hubR + 3f, Offset(cx + 2f, cy + 2f))
    drawCircle(Color(0xFF1A1A32), hubR + 1f, center)
    drawCircle(Color(0xFF18182E), hubR, center)
    drawCircle(Color(0xFF141428), hubR * 0.8f, center)
    drawCircle(Color(0xFF2A2A48).copy(alpha = 0.4f), hubR * 0.8f, center, style = Stroke(1f))
    drawCircle(Color(0xFF101022), hubR * 0.5f, center)

    drawContext.canvas.nativeCanvas.let { canvas ->
        val paint = android.graphics.Paint().apply {
            color = if (engaged) accent.hashCode() else Color(0xFF4A4A6E).hashCode()
            textSize = hubR * 0.55f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        canvas.drawText("OW", cx, cy + hubR * 0.18f, paint)
    }
}

// ===== 12 O'CLOCK YELLOW MARKER =====
private fun DrawScope.drawTopMarker(
    cx: Float, cy: Float, r: Float, rimW: Float, rotDeg: Float
) {
    val rad = (-90f + rotDeg) * PI.toFloat() / 180f
    val innerR = r - rimW / 2f - 2f
    val outerR = r + rimW / 2f + 2f
    val p1 = Offset(cx + cos(rad) * innerR, cy + sin(rad) * innerR)
    val p2 = Offset(cx + cos(rad) * outerR, cy + sin(rad) * outerR)

    drawLine(Color(0xFFFBBF24).copy(alpha = 0.25f), p1, p2, 14f, StrokeCap.Round)
    drawLine(Color(0xFFFBBF24), p1, p2, 6f, StrokeCap.Round)
    drawLine(Color(0xFFFDE68A), p1, p2, 2f, StrokeCap.Round)
}

// ===== TICK MARKS =====
private fun DrawScope.drawTicks(
    cx: Float, cy: Float, r: Float, rimW: Float, rotDeg: Float, accent: Color
) {
    data class Tick(val deg: Float, val len: Float, val col: Color, val w: Float)
    val ticks = listOf(
        Tick(-90f, 4f, Color(0xFFEF4444), 2f),
        Tick(-60f, 3f, Color(0xFFF59E0B), 1.5f),
        Tick(-30f, 2f, Color(0xFF4A4A6E), 1.5f),
        Tick(0f, 5f, accent, 2.5f),
        Tick(30f, 2f, Color(0xFF4A4A6E), 1.5f),
        Tick(60f, 3f, Color(0xFFF59E0B), 1.5f),
        Tick(90f, 4f, Color(0xFFEF4444), 2f),
    )
    for ((deg, len, col, w) in ticks) {
        val rad = (deg + rotDeg) * PI.toFloat() / 180f
        val tInner = r + rimW / 2f + 3f
        val tOuter = tInner + len
        drawLine(col,
            Offset(cx + cos(rad) * tInner, cy + sin(rad) * tInner),
            Offset(cx + cos(rad) * tOuter, cy + sin(rad) * tOuter), w)
    }
}

// ===== ANGLE DISPLAY =====
private fun DrawScope.drawAngleDisplay(
    cx: Float, cy: Float, hubR: Float, r: Float,
    angle: Float, gyro: Boolean, touching: Boolean, accent: Color
) {
    val deg = (angle * 900f).toInt()
    val col = when { gyro -> Color(0xFF22D3EE); touching -> Color.White; else -> Color(0xFF555555) }
    drawContext.canvas.nativeCanvas.let { c ->
        val p = android.graphics.Paint().apply {
            color = col.hashCode()
            textSize = r * 0.16f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setShadowLayer(3f, 0f, 0f, Color.Black.hashCode())
        }
        c.drawText("${deg}deg", cx, cy + hubR + r * 0.16f, p)
    }
}