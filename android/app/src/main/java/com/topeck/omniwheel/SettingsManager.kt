package com.topeck.omniwheel

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent settings stored in SharedPreferences.
 * Organized into categories matching the settings UI sections.
 */
class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("omniwheel_settings", Context.MODE_PRIVATE)
    
    // === STEERING ===
    var steeringMaxAngle: Int
        get() = prefs.getInt("steering_max_angle", 900)
        set(v) = prefs.edit().putInt("steering_max_angle", v).apply()
    
    var steeringSensitivity: Float
        get() = prefs.getFloat("steering_sensitivity", 1.0f)
        set(v) = prefs.edit().putFloat("steering_sensitivity", v).apply()
    
    var steeringDeadzone: Float
        get() = prefs.getFloat("steering_deadzone", 0.015f)
        set(v) = prefs.edit().putFloat("steering_deadzone", v).apply()
    
    var steeringSmoothing: Float
        get() = prefs.getFloat("steering_smoothing", 0.04f)
        set(v) = prefs.edit().putFloat("steering_smoothing", v).apply()
    
    var springStrength: Float
        get() = prefs.getFloat("spring_strength", 6.0f)
        set(v) = prefs.edit().putFloat("spring_strength", v).apply()
    
    var springDamping: Float
        get() = prefs.getFloat("spring_damping", 4.5f)
        set(v) = prefs.edit().putFloat("spring_damping", v).apply()
    
    var touchDragZone: Float
        get() = prefs.getFloat("touch_drag_zone", 0.4f)
        set(v) = prefs.edit().putFloat("touch_drag_zone", v).apply()
    
    // === PEDALS ===
    var pedalWidth: Int
        get() = prefs.getInt("pedal_width", 68)
        set(v) = prefs.edit().putInt("pedal_width", v).apply()
    
    var pedalReturnOnRelease: Boolean
        get() = prefs.getBoolean("pedal_return", true)
        set(v) = prefs.edit().putBoolean("pedal_return", v).apply()
    
    var throttleMaxByte: Int
        get() = prefs.getInt("throttle_max", 255)
        set(v) = prefs.edit().putInt("throttle_max", v).apply()
    
    var clutchEnabled: Boolean
        get() = prefs.getBoolean("clutch_enabled", false)
        set(v) = prefs.edit().putBoolean("clutch_enabled", v).apply()
    
    // === GYROSCOPE ===
    var gyroEnabled: Boolean
        get() = prefs.getBoolean("gyro_enabled", false)
        set(v) = prefs.edit().putBoolean("gyro_enabled", v).apply()
    
    var gyroMaxTiltDeg: Float
        get() = prefs.getFloat("gyro_max_tilt", 25f)
        set(v) = prefs.edit().putFloat("gyro_max_tilt", v).apply()
    
    var gyroSensitivity: Float
        get() = prefs.getFloat("gyro_sensitivity", 1.8f)
        set(v) = prefs.edit().putFloat("gyro_sensitivity", v).apply()
    
    var gyroDeadzoneDeg: Float
        get() = prefs.getFloat("gyro_deadzone", 2.0f)
        set(v) = prefs.edit().putFloat("gyro_deadzone", v).apply()
    
    var gyroFilterAlpha: Float
        get() = prefs.getFloat("gyro_filter_alpha", 0.25f)
        set(v) = prefs.edit().putFloat("gyro_filter_alpha", v).apply()
    
    var gyroSmoothAlpha: Float
        get() = prefs.getFloat("gyro_smooth_alpha", 0.22f)
        set(v) = prefs.edit().putFloat("gyro_smooth_alpha", v).apply()
    
    // === NETWORK ===
    var sendRateHz: Int
        get() = prefs.getInt("send_rate_hz", 240)
        set(v) = prefs.edit().putInt("send_rate_hz", v).apply()
    
    var heartbeatIntervalMs: Int
        get() = prefs.getInt("heartbeat_interval", 1000)
        set(v) = prefs.edit().putInt("heartbeat_interval", v).apply()
    
    // === DISPLAY ===
    var showAngleText: Boolean
        get() = prefs.getBoolean("show_angle_text", true)
        set(v) = prefs.edit().putBoolean("show_angle_text", v).apply()
    
    var showModeIndicator: Boolean
        get() = prefs.getBoolean("show_mode_indicator", true)
        set(v) = prefs.edit().putBoolean("show_mode_indicator", v).apply()
    
    var showPacketCounter: Boolean
        get() = prefs.getBoolean("show_packet_counter", true)
        set(v) = prefs.edit().putBoolean("show_packet_counter", v).apply()
    
    var statusBarHeight: Int
        get() = prefs.getInt("status_bar_height", 26)
        set(v) = prefs.edit().putInt("status_bar_height", v).apply()
    
    // === BUTTONS ===
    var buttonSizeTop: Int
        get() = prefs.getInt("btn_size_top", 48)
        set(v) = prefs.edit().putInt("btn_size_top", v).apply()
    
    var buttonSizeBottom: Int
        get() = prefs.getInt("btn_size_bottom", 42)
        set(v) = prefs.edit().putInt("btn_size_bottom", v).apply()
    
    var hapticFeedback: Boolean
        get() = prefs.getBoolean("haptic_feedback", true)
        set(v) = prefs.edit().putBoolean("haptic_feedback", v).apply()
    
    // === MISC ===
    var lastUsedIp: String
        get() = prefs.getString("last_used_ip", "") ?: ""
        set(v) = prefs.edit().putString("last_used_ip", v).apply()

    // === PROFILES ===
    var activeProfile: String
        get() = prefs.getString("active_profile", "Default") ?: "Default"
        set(v) = prefs.edit().putString("active_profile", v).apply()

    // === HUD LAYOUT ===
    fun saveHudLayout(widgets: List<HudWidget>) {
        prefs.edit().putString("hud_layout", widgetListToJson(widgets)).apply()
    }

    fun loadHudLayout(): List<HudWidget> {
        val json = prefs.getString("hud_layout", "") ?: ""
        return widgetListFromJson(json) ?: loadDefaultHudLayout()
    }

    // === DEVELOPER: DEFAULT LAYOUT ===
    // The stored default layout is used on first launch / after a reset,
    // exactly like the built-in defaultControllerLayout() fallback.
    var defaultHudLayoutJson: String?
        get() = prefs.getString("default_hud_layout", null)
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove("default_hud_layout") else e.putString("default_hud_layout", v)
            e.apply()
        }

    fun loadDefaultHudLayout(): List<HudWidget> {
        val j = prefs.getString("default_hud_layout", "")
        return widgetListFromJson(j ?: "") ?: defaultControllerLayout()
    }

    fun saveCurrentAsDefaultLayout() {
        val json = prefs.getString("hud_layout", "")
        if (!json.isNullOrBlank()) {
            prefs.edit().putString("default_hud_layout", json).apply()
        }
    }

    // Write the current layout JSON to a file the developer can grab.
    fun exportLayoutJson(context: Context): String? {
        var json = prefs.getString("hud_layout", "").orEmpty()
        if (json.isBlank()) json = prefs.getString("default_hud_layout", "").orEmpty()
        if (json.isBlank()) return null
        return try {
            val dir = context.getExternalFilesDir(null)
                ?: context.filesDir
            val file = java.io.File(dir, "omniwheel_hud_layout.json")
            file.writeText(json)
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
    
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
    
    fun exportSettings(): String {
        val sb = StringBuilder()
        for ((key, value) in prefs.all) {
            sb.append("$key=$value\n")
        }
        return sb.toString()
    }
}