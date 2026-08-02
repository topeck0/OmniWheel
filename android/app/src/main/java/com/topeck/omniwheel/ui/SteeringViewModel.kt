package com.topeck.omniwheel.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.topeck.omniwheel.network.InputSender
import kotlin.math.*

/**
 * Steering physics engine with spring-return behavior.
 * ZERO-LATENCY: Touch moves write directly to InputSender immediately,
 * bypassing any polling loop. The 240Hz physics only handles spring-return.
 */
class SteeringViewModel : ViewModel() {
    
    companion object {
        private const val MAX_STEERING = 32767
        private const val PHYSICS_FPS = 240
        private const val DT = 1.0 / PHYSICS_FPS
    }
    
    // Tuned for instant response
    var maxAngleDeg = 900f
    var sensitivity = 1.0f
    var deadzone = 0.015f
    var springStrength = 6.0f      // faster return
    var damping = 4.5f              // snappier stop
    var smoothing = 0.04f          // near-instant
    
    // Direct input sender reference for zero-latency writes
    var inputSender: InputSender? = null
    
    // State
    private var currentAngle = 0f
    private var velocity = 0f
    private var smoothedOutput = 0f
    private var isTouching = false
    private var touchStartX = 0f
    private var angleAtTouchStart = 0f
    private var screenCenterX = 0f
    private var screenWidth = 1f
    
    // Direct output — bypass smoothing when touching for zero-latency feel
    val outputAngle: Float get() = if (isTouching) currentAngle else smoothedOutput
    val rawAngle: Float get() = currentAngle
    
    fun onTouchStart(x: Float, centerX: Float, width: Float) {
        isTouching = true
        touchStartX = x
        angleAtTouchStart = currentAngle
        screenCenterX = centerX
        screenWidth = width
        velocity = 0f
        smoothedOutput = currentAngle
    }
    
    fun onTouchMove(x: Float) {
        if (!isTouching) return
        val deltaPixels = x - touchStartX
        val deltaNorm = (deltaPixels / (screenWidth * 0.4f)) * sensitivity
        currentAngle = (angleAtTouchStart + deltaNorm).coerceIn(-1f, 1f)
        velocity = 0f
        
        // ZERO-LATENCY: Write steering directly to input sender IMMEDIATELY
        inputSender?.let { sender ->
            val val0 = if (abs(currentAngle) < deadzone) 0f else currentAngle
            sender.steering = (val0 * MAX_STEERING).toInt().coerceIn(-MAX_STEERING, MAX_STEERING).toShort()
        }
    }
    
    fun onTouchEnd() {
        isTouching = false
    }
    
    fun updatePhysics() {
        if (isTouching) {
            // While touching, no physics — direct control (zero latency)
            return
        }
        
        val dist = abs(currentAngle)
        
        if (dist < 0.001f) {
            currentAngle = 0f
            velocity = 0f
            smoothedOutput = smoothedOutput + (0f - smoothedOutput) * (1f - smoothing)
            // Write zero back
            inputSender?.steering = 0
            return
        }
        
        // Spring force with bell profile
        val bellFactor = sin(dist * PI.toFloat()).toFloat()
        val linearForce = -springStrength * currentAngle
        val totalForce = linearForce * (0.3f + 0.7f * bellFactor)
        
        // Damping — stronger near center for quick settle
        val proximityDamp = if (dist < 0.15f) {
            damping * (1f + (0.15f - dist) / 0.15f * 4f)
        } else {
            damping.toFloat()
        }
        
        val dampForce = -proximityDamp * velocity
        
        val acceleration = totalForce + dampForce
        velocity += (acceleration * DT).toFloat()
        currentAngle += (velocity * DT).toFloat()
        
        if (currentAngle > 1f) { currentAngle = 1f; velocity = -velocity * 0.05f }
        else if (currentAngle < -1f) { currentAngle = -1f; velocity = -velocity * 0.05f }
        
        // Light smoothing only during spring return
        smoothedOutput = smoothedOutput + (currentAngle - smoothedOutput) * (1f - smoothing)
        
        // Write spring-return output directly
        inputSender?.let { sender ->
            val val0 = if (abs(smoothedOutput) < deadzone) 0f else smoothedOutput
            sender.steering = (val0 * MAX_STEERING).toInt().coerceIn(-MAX_STEERING, MAX_STEERING).toShort()
        }
    }
    
    fun getSteeringShort(): Short {
        val output = if (abs(smoothedOutput) < deadzone) 0f else smoothedOutput
        return (output * MAX_STEERING).toInt().coerceIn(-MAX_STEERING, MAX_STEERING).toShort()
    }
    
    fun reset() {
        currentAngle = 0f
        velocity = 0f
        smoothedOutput = 0f
        isTouching = false
    }
}

@Composable
fun rememberSteeringViewModel(): SteeringViewModel {
    return remember { SteeringViewModel() }
}