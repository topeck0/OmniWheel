package com.topeck.omniwheel.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.topeck.omniwheel.network.InputSender
import kotlin.math.*

/**
 * Rotation-based steering physics engine.
 * The user rotates the wheel like a real steering wheel — touch anywhere on the wheel
 * and rotate clockwise/counter-clockwise. The angle delta from the wheel center
 * determines steering input.
 *
 * ZERO-LATENCY: Touch moves write directly to InputSender immediately.
 */
class SteeringViewModel : ViewModel() {
    
    companion object {
        private const val MAX_STEERING = 32767
        private const val PHYSICS_FPS = 240
        private const val DT = 1.0 / PHYSICS_FPS
        // How many radians of wheel rotation = full steering lock (-1..1)
        // ~2.5 full rotations (900 deg) maps to full lock
        private const val RAD_PER_FULL_LOCK = (900f * PI.toFloat() / 180f) / 1.0f
    }
    
    var maxAngleDeg = 900f
    var sensitivity = 1.0f
    var deadzone = 0.015f
    var springStrength = 6.0f
    var damping = 4.5f
    var smoothing = 0.04f
    
    var inputSender: InputSender? = null
    
    // State
    private var currentAngle = 0f  // -1..1 normalized steering
    private var velocity = 0f
    private var smoothedOutput = 0f
    private var isTouching = false
    
    // Rotation tracking
    private var touchStartAngleRad = 0f  // angle from wheel center at touch start
    private var angleAtTouchStart = 0f  // steering value at touch start
    
    val outputAngle: Float get() = if (isTouching) currentAngle else smoothedOutput
    val rawAngle: Float get() = currentAngle
    
    /**
     * Called when touch starts on the wheel.
     * @param touchX touch position X relative to wheel center
     * @param touchY touch position Y relative to wheel center
     */
    fun onRotationTouchStart(touchX: Float, touchY: Float) {
        isTouching = true
        touchStartAngleRad = atan2(touchY, touchX)
        angleAtTouchStart = currentAngle
        velocity = 0f
        smoothedOutput = currentAngle
    }
    
    /**
     * Called when touch drags on the wheel.
     * Computes angle delta from wheel center to determine rotation.
     * @param touchX current touch X relative to wheel center
     * @param touchY current touch Y relative to wheel center
     */
    fun onRotationTouchMove(touchX: Float, touchY: Float) {
        if (!isTouching) return
        
        val currentAngleRad = atan2(touchY, touchX)
        var deltaRad = currentAngleRad - touchStartAngleRad
        
        // Handle wrap-around (e.g., from -PI to +PI or vice versa)
        if (deltaRad > PI.toFloat()) deltaRad -= 2f * PI.toFloat()
        if (deltaRad < -PI.toFloat()) deltaRad += 2f * PI.toFloat()
        
        // Convert rotation to steering: normalize so that ~2.5 rotations = full lock
        val maxAngleRad = (maxAngleDeg * PI.toFloat() / 180f)
        val deltaNorm = (deltaRad / maxAngleRad) * sensitivity
        
        currentAngle = (angleAtTouchStart + deltaNorm).coerceIn(-1f, 1f)
        velocity = 0f
        
        // ZERO-LATENCY: Write steering directly
        inputSender?.let { sender ->
            val val0 = if (abs(currentAngle) < deadzone) 0f else currentAngle
            sender.steering = (val0 * MAX_STEERING).toInt().coerceIn(-MAX_STEERING, MAX_STEERING).toShort()
        }
    }
    
    fun onTouchEnd() {
        isTouching = false
    }
    
    fun updatePhysics() {
        if (isTouching) return
        
        val dist = abs(currentAngle)
        
        if (dist < 0.001f) {
            currentAngle = 0f
            velocity = 0f
            smoothedOutput = smoothedOutput + (0f - smoothedOutput) * (1f - smoothing)
            inputSender?.steering = 0
            return
        }
        
        val bellFactor = sin(dist * PI.toFloat()).toFloat()
        val linearForce = -springStrength * currentAngle
        val totalForce = linearForce * (0.3f + 0.7f * bellFactor)
        
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
        
        smoothedOutput = smoothedOutput + (currentAngle - smoothedOutput) * (1f - smoothing)
        
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