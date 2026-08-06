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
 * Default layout that reproduces the reference ControllerScreen arrangement.
 * Fractions are relative to the play area (nominal reference 960 x 514).
 */
fun defaultControllerLayout(): List<HudWidget> = listOf(
    // Steering wheel (center-left zone)
    HudWidget("steering", "Steering", 0.205f, 0.47f, 0.32f, 0.62f, 0, 1f, isCircular = true, isSteering = true),
    // Pedals (far right vertical column). Default size 120x470dp.
    HudWidget("clutch", "CLUTCH", 0.6675f, 0.56f, PEDAL_DEF_W_DP / HUD_REF_W, PEDAL_DEF_H_DP / HUD_REF_H, 0, 1f, isPedal = true),
    HudWidget("brake", "BRAKE", 0.8025f, 0.56f, PEDAL_DEF_W_DP / HUD_REF_W, PEDAL_DEF_H_DP / HUD_REF_H, 0, 1f, isPedal = true),
    HudWidget("gas", "GAS", 0.9375f, 0.56f, PEDAL_DEF_W_DP / HUD_REF_W, PEDAL_DEF_H_DP / HUD_REF_H, 0, 1f, isPedal = true),
    // Buttons: 75x84dp default (wFrac = 75/960 = 0.078, hFrac = 84/540 = 0.156).
    // Placed in a 4x5 grid with enough spacing so they never overlap.
    HudWidget("btn_5", "5", 0.43f, 0.13f, 0.078f, 0.156f, 5),
    HudWidget("btn_6", "6", 0.51f, 0.13f, 0.078f, 0.156f, 6),
    HudWidget("btn_7", "7", 0.59f, 0.13f, 0.078f, 0.156f, 7),
    HudWidget("btn_18", "18", 0.67f, 0.13f, 0.078f, 0.156f, 18),
    HudWidget("btn_4", "4", 0.43f, 0.31f, 0.078f, 0.156f, 4),
    HudWidget("btn_8", "8", 0.51f, 0.31f, 0.078f, 0.156f, 8),
    HudWidget("btn_1", "1", 0.59f, 0.31f, 0.078f, 0.156f, 1),
    HudWidget("btn_3", "3", 0.67f, 0.31f, 0.078f, 0.156f, 3),
    HudWidget("btn_2", "2", 0.43f, 0.49f, 0.078f, 0.156f, 2),
    HudWidget("btn_13", "13", 0.51f, 0.49f, 0.078f, 0.156f, 13),
    HudWidget("btn_9", "9", 0.59f, 0.49f, 0.078f, 0.156f, 9),
    HudWidget("btn_15", "15", 0.67f, 0.49f, 0.078f, 0.156f, 15),
    HudWidget("btn_10", "10", 0.43f, 0.67f, 0.078f, 0.156f, 10),
    HudWidget("btn_11", "11", 0.51f, 0.67f, 0.078f, 0.156f, 11),
    HudWidget("btn_12", "12", 0.59f, 0.67f, 0.078f, 0.156f, 12),
    HudWidget("btn_17", "17", 0.67f, 0.67f, 0.078f, 0.156f, 17),
    HudWidget("btn_14", "14", 0.43f, 0.85f, 0.078f, 0.156f, 14)
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
