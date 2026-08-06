package com.topeck.omniwheel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single HUD element. Positions and sizes are stored as fractions (0..1)
 * of the play area so the editor and the game screen place them identically.
 */
data class HudWidget(
    val id: String,
    val label: String,
    var cx: Float,
    var cy: Float,
    var wFrac: Float,
    var hFrac: Float,
    var vJoyBtn: Int,
    var scale: Float = 1.0f,
    val isCircular: Boolean = false,
    val isSteering: Boolean = false,
    val isPedal: Boolean = false
)

// Reference nominal play area used for the dp readouts in the editor.
const val HUD_REF_W = 960f
const val HUD_REF_H = 540f

// Per-type reset defaults (also used as the fresh-install default sizes).
const val BUTTON_DEF_W_DP = 75f
const val BUTTON_DEF_H_DP = 84f
const val PEDAL_DEF_W_DP = 120f
const val PEDAL_DEF_H_DP = 470f

// Slider (pedal) range readouts, in dp.
const val PEDAL_W_MIN_DP = 70f
const val PEDAL_W_MAX_DP = 500f
const val PEDAL_H_MIN_DP = 200f
const val PEDAL_H_MAX_DP = 700f

fun HudWidget.resetToTypeDefaults(): HudWidget {
    return if (isSteering) {
        copy(scale = 1.0f)
    } else if (isPedal) {
        copy(scale = 1.0f, wFrac = PEDAL_DEF_W_DP / HUD_REF_W, hFrac = PEDAL_DEF_H_DP / HUD_REF_H)
    } else {
        copy(scale = 1.0f, wFrac = BUTTON_DEF_W_DP / HUD_REF_W, hFrac = BUTTON_DEF_H_DP / HUD_REF_H)
    }
}

/**
 * Default layout (built-in) — this is the phone layout the user settled on.
 * Fractions are relative to the play area (nominal reference 960 x 540).
 */
fun defaultControllerLayout(): List<HudWidget> = listOf(
    // Steering wheel (center-left zone)
    HudWidget("steering", "Steering", 0.1707f, 0.5969f, 0.3200f, 0.6200f, 0, 1f, isCircular = true, isSteering = true),
    // Pedals
    HudWidget("clutch", "CLUTCH", 0.1884f, 0.5794f, 0.1250f, 0.5852f, 0, 1f, isPedal = true),
    HudWidget("brake", "BRAKE", 0.7941f, 0.5471f, 0.0986f, 0.6645f, 0, 0.9982f, isPedal = true),
    HudWidget("gas", "GAS", 0.9107f, 0.5510f, 0.0986f, 0.6645f, 0, 1f, isPedal = true),
    // Buttons
    HudWidget("btn_1", "9", 0.9057f, 0.0619f, 0.1385f, 0.2444f, 9),
    HudWidget("btn_2", "10", 0.7491f, 0.0640f, 0.1354f, 0.2574f, 10),
    HudWidget("btn_3", "11", 0.6040f, 0.0870f, 0.1094f, 0.2204f, 11),
    HudWidget("btn_4", "4", 0.0352f, 0.3200f, 0.0781f, 0.1556f, 4),
    HudWidget("btn_5", "5", 0.3164f, 0.3285f, 0.0781f, 0.1556f, 5),
    HudWidget("btn_6", "6", 0.0971f, 0.1672f, 0.0781f, 0.1556f, 6),
    HudWidget("btn_7", "7", 0.1796f, 0.1644f, 0.0781f, 0.1556f, 7),
    HudWidget("btn_8", "8", 0.2628f, 0.1672f, 0.0781f, 0.1556f, 8),
    HudWidget("btn_9", "1", 0.6554f, 0.6198f, 0.1042f, 0.1852f, 1),
    HudWidget("btn_10", "2", 0.5709f, 0.4205f, 0.0781f, 0.1556f, 2),
    HudWidget("btn_11", "3", 0.6612f, 0.8448f, 0.0781f, 0.1556f, 3),
    HudWidget("btn_12", "12", 0.0325f, 0.9146f, 0.0500f, 0.1056f, 12),
    HudWidget("btn_13", "14", 0.4288f, 0.7018f, 0.0854f, 0.1556f, 14),
    HudWidget("btn_14", "15", 0.5198f, 0.7007f, 0.0917f, 0.1556f, 15),
    HudWidget("btn_15", "16", 0.4740f, 0.8625f, 0.1813f, 0.1556f, 16),
    HudWidget("btn_16", "13", 0.0157f, 0.7715f, 0.0781f, 0.1556f, 13)
)

fun HudWidget.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("label", label)
    put("cx", cx.toDouble())
    put("cy", cy.toDouble())
    put("wFrac", wFrac.toDouble())
    put("hFrac", hFrac.toDouble())
    put("vJoyBtn", vJoyBtn)
    put("scale", scale.toDouble())
    put("circular", isCircular)
    put("steering", isSteering)
    put("pedal", isPedal)
}

fun widgetListToJson(widgets: List<HudWidget>): String {
    val arr = JSONArray()
    widgets.forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun widgetListFromJson(json: String): List<HudWidget>? {
    if (json.isBlank()) return null
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HudWidget(
                id = o.getString("id"),
                label = o.getString("label"),
                cx = o.getDouble("cx").toFloat(),
                cy = o.getDouble("cy").toFloat(),
                wFrac = o.getDouble("wFrac").toFloat(),
                hFrac = o.getDouble("hFrac").toFloat(),
                vJoyBtn = o.getInt("vJoyBtn"),
                scale = o.optDouble("scale", 1.0).toFloat(),
                isCircular = o.optBoolean("circular"),
                isSteering = o.optBoolean("steering"),
                isPedal = o.optBoolean("pedal")
            )
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Places a widget in the play area using the same fraction math everywhere,
 * guaranteeing the editor and the game screen render identical positions.
 * Scale is applied around the widget center (transformOrigin = Center).
 * Steering wheels are forced to a square so they always render as a circle.
 */
@Composable
fun HudSlot(
    widget: HudWidget,
    areaWidth: Dp,
    areaHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val awPx = with(density) { areaWidth.toPx() }
    val ahPx = with(density) { areaHeight.toPx() }
    var wPx = awPx * widget.wFrac
    var hPx = ahPx * widget.hFrac
    if (widget.isSteering) {
        val m = minOf(wPx, hPx)
        wPx = m
        hPx = m
    }
    Box(
        modifier = modifier
            .offset(
                x = with(density) { (awPx * widget.cx - wPx / 2f).toDp() },
                y = with(density) { (ahPx * widget.cy - hPx / 2f).toDp() }
            )
            .size(
                width = with(density) { wPx.toDp() },
                height = with(density) { hPx.toDp() }
            )
            .graphicsLayer {
                scaleX = widget.scale
                scaleY = widget.scale
                transformOrigin = TransformOrigin.Center
            }
    ) { content() }
}
