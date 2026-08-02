package com.topeck.omniwheel.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.topeck.omniwheel.network.InputSender
import kotlin.math.*

/**
 * Rotation-based steering physics engine.
 * Visual rotation tracks the finger exactly (1:1).
 * Steering output is normalized and sent to the receiver.
 */
class SteeringViewModel : ViewModel() {
    
    companion object {
        private const val MAX_STEERING = 32767
        private const val PHYSICS_FPS = 240
        private const val DT = 1.0 / PHYSICS_FPS
    }
    
    var maxAngleDeg = 900f
    var sensitivity = 1.0f
    var deadzone = 0.015f
    var springStrength = 6.0f
    var damping = 4.5f
    var smoothing = 0.04f
    
    var inputSender: InputSender? = null
    
    // Internal state
    private var currentAngle = 0f  // -1..1 normalized steering
    private var velocity = 0f
    private var isTouching = false
    
    // Incremental rotation tracking
    private var prevTouchAngleRad = 0f
    private var angleAtTouchStart = 0f
    private var accumulatedRad = 0f
    
    // Smoothed output for when not touching (spring return)
    private var smoothedOutput = 0f
    
    // Visual rotation in degrees — tracks finger 1:1 when touching,
    // spring-returns to 0 when released. This is what the Image rotates by.
    private val _visualRotationDeg = mutableStateOf(0f)
    val visualRotationDeg: State<Float> = _visualRotationDeg

       // Normalized angle for display text
    private val _displayAngle = mutableStateOf(0f)
    val displayAngle: State<Float> = _displayAngle
    
    val outputAngle: Float get() = if (isTouching) currentAngle else smoothedOutput
    val rawAngle: Float get() = currentAngle
    
    private fun publish() {
        if (isTouching) {
            // Visual follows finger exactly
            _visualRotationDeg.value = accumulatedRad * 180f / PI.toFloat()
            _displayAngle.value = currentAngle
        } else {
            // Visual spring-returns: map smoothedOutput back to degrees
            _visualRotationDeg.value = smoothedOutput * maxAngleDeg
            _displayAngle.value = smoothedOutput
        }
    }
    
    fun onRotationTouchStart(touchX: Float, touchY: Float) {
        isTouching = true
        prevTouchAngleRad = atan2(touchY, touchX)
        angleAtTouchStart = currentAngle
        accumulatedRad = 0f
        velocity = 0f
        smoothedOutput = currentAngle
        publish()
    }
    
    fun onRotationTouchMove(touchX: Float, touchY: Float) {
        if (!isTouching) return
        
        val newAngleRad = atan2(touchY, touchX)
        var deltaRad = newAngleRad - prevTouchAngleRad
        
        if (deltaRad > PI.toFloat()) deltaRad -= 2f * PI.toFloat()
        if (deltaRad < -PI.toFloat()) deltaRad += 2f * PI.toFloat()
        
        accumulatedRad += deltaRad
        prevTouchAngleRad = newAngleRad
        
        // Convert to normalized steering (-1..1)
        val maxAngleRad = maxAngleDeg * PI.toFloat() / 180f
        val normalizedDelta = (accumulatedRad / maxAngleRad) * sensitivity
        currentAngle = (angleAtTouchStart + normalizedDelta).coerceIn(-1f, 1f)
        velocity = 0f
        
        // ZERO-LATENCY: send steering directly
        inputSender?.let { sender ->
            val val0 = if (abs(currentAngle) < deadzone) 0f else currentAngle
            sender.steering = (val0 * MAX_STEERING).toInt().coerceIn(-MAX_STEERING, MAX_STEERING).toShort()
        }
        
        publish()
    }
    
    fun onTouchEnd() {
        isTouching = false
        publish()
    }
    
    fun updatePhysics() {
        if (isTouching) return
        
        val dist = abs(currentAngle)
        
        if (dist < 0.001f) {
            currentAngle = 0f
            velocity = 0f
            smoothedOutput = smoothedOutput + (0f - smoothedOutput) * (1f - smoothing)
            if (abs(smoothedOutput) < 0.0005f) smoothedOutput = 0f
            inputSender?.steering = 0
            publish()
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
        
        publish()
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
        publish()
    }
}

@Composable
fun rememberSteeringViewModel(): SteeringViewModel {
    return remember { SteeringViewModel() }
}