package com.topeck.omniwheel.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.topeck.omniwheel.network.InputSender
import kotlin.math.*

/**
 * Rotation-based steering physics engine.
 * Visual rotation tracks the finger 1:1, clamped to ±maxAngleDeg.
 * Spring return with zero-crossing snap to prevent overshoot.
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
    
    // Incremental rotation tracking for full 900-degree support
    private var prevTouchAngleRad = 0f
    private var accumulatedRad = 0f
    
    // Smoothed output for when not touching (spring return)
    private var smoothedOutput = 0f
    
    // Visual rotation in degrees — clamped to ±maxAngleDeg
    private val _visualRotationDeg = mutableStateOf(0f)
    val visualRotationDeg: State<Float> = _visualRotationDeg

    // Normalized angle for display text
    private val _displayAngle = mutableStateOf(0f)
    val displayAngle: State<Float> = _displayAngle
    
    val outputAngle: Float get() = if (isTouching) currentAngle else smoothedOutput
    val rawAngle: Float get() = currentAngle
    
    private fun publish() {
        if (isTouching) {
            val rawDeg = accumulatedRad * 180f / PI.toFloat()
            _visualRotationDeg.value = rawDeg.coerceIn(-maxAngleDeg, maxAngleDeg)
            _displayAngle.value = currentAngle
        } else {
            _visualRotationDeg.value = smoothedOutput * maxAngleDeg
            _displayAngle.value = smoothedOutput
        }
    }
    
    fun onRotationTouchDown(touchX: Float, touchY: Float) {
        isTouching = true
        velocity = 0f
        // Instantly hold position at current wheel position (static or spring-returning)
        currentAngle = smoothedOutput
        accumulatedRad = smoothedOutput * maxAngleDeg * PI.toFloat() / 180f
        prevTouchAngleRad = atan2(touchY, touchX)
        publish()
    }
    
    fun onRotationTouchStart(touchX: Float, touchY: Float) {
        onRotationTouchDown(touchX, touchY)
    }
    
    fun onRotationTouchMove(touchX: Float, touchY: Float) {
        if (!isTouching) return
        
        val newAngleRad = atan2(touchY, touchX)
        var deltaRad = newAngleRad - prevTouchAngleRad
        
        if (deltaRad > PI.toFloat()) deltaRad -= 2f * PI.toFloat()
        if (deltaRad < -PI.toFloat()) deltaRad += 2f * PI.toFloat()
        
        accumulatedRad += deltaRad
        prevTouchAngleRad = newAngleRad
        
        val maxAngleRad = maxAngleDeg * PI.toFloat() / 180f
        accumulatedRad = accumulatedRad.coerceIn(-maxAngleRad, maxAngleRad)
        
        val normalizedDelta = (accumulatedRad / maxAngleRad) * sensitivity
        currentAngle = normalizedDelta.coerceIn(-1f, 1f)
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
        smoothedOutput = currentAngle
        publish()
    }
    
    fun updatePhysics() {
        if (isTouching) return
        
        // Constant spring force
        val springForce = -springStrength * currentAngle
        
        // Constant damping
        val dampForce = -damping * velocity
        
        val acceleration = springForce + dampForce
        velocity += (acceleration * DT).toFloat()
        currentAngle += (velocity * DT).toFloat()
        
        // Hard clamp at limits
        if (currentAngle > 1f) { currentAngle = 1f; velocity = -velocity * 0.05f }
        else if (currentAngle < -1f) { currentAngle = -1f; velocity = -velocity * 0.05f }
        
        smoothedOutput = smoothedOutput + (currentAngle - smoothedOutput) * (1f - smoothing)
        
        // Gently settle to zero when extremely close
        if (abs(smoothedOutput) < 0.001f && abs(currentAngle) < 0.001f && abs(velocity) < 0.05f) {
            currentAngle = 0f
            smoothedOutput = 0f
            velocity = 0f
        }
        
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
    
    fun setGyroAngle(angleNormalized: Float) {
        smoothedOutput = angleNormalized.coerceIn(-1f, 1f)
        _displayAngle.value = smoothedOutput
        _visualRotationDeg.value = smoothedOutput * maxAngleDeg
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