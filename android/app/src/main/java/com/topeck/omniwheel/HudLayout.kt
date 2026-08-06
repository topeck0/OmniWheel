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

/**
 * Default layout that reproduces the reference ControllerScreen arrangement.
 * Fractions are relative to the play area (nominal reference 960 x 514).
 */
fun defaultControllerLayout(): List<HudWidget> = listOf(
    // Steering wheel (center of left zone)
    HudWidget("steering", "Steering", 0.205f, 0.47f, 0.32f, 0.62f, 0, 1f, isCircular = true, isSteering = true),
    // Pedals (far right vertical column)
    HudWidget("clutch", "CLUTCH", 0.78f, 0.56f, 0.07f, 0.88f, 0, 1f, isPedal = true),
    HudWidget("brake", "BRAKE", 0.876f, 0.56f, 0.07f, 0.88f, 0, 1f, isPedal = true),
    HudWidget("gas", "GAS", 0.972f, 0.56f, 0.07f, 0.88f, 0, 1f, isPedal = true),
    // Top row: 5, 6, 7, 18
    HudWidget("btn_5", "5", 0.137f, 0.06f, 0.047f, 0.105f, 5),
    HudWidget("btn_6", "6", 0.186f, 0.06f, 0.047f, 0.105f, 6),
    HudWidget("btn_7", "7", 0.235f, 0.06f, 0.047f, 0.105f, 7),
    HudWidget("btn_18", "18", 0.286f, 0.06f, 0.049f, 0.11f, 18),
    // Button 4 (left of wheel)
    HudWidget("btn_4", "4", 0.022f, 0.10f, 0.047f, 0.105f, 4),
    // Button 8 (right of wheel, below 18)
    HudWidget("btn_8", "8", 0.40f, 0.105f, 0.049f, 0.11f, 8),
    // Button 14 (bottom-left)
    HudWidget("btn_14", "14", 0.022f, 0.955f, 0.047f, 0.105f, 14),
    // Center-right grid
    HudWidget("btn_1", "1", 0.434f, 0.06f, 0.052f, 0.117f, 1),
    HudWidget("btn_3", "3", 0.506f, 0.06f, 0.052f, 0.117f, 3),
    HudWidget("btn_2", "2", 0.578f, 0.06f, 0.052f, 0.117f, 2),
    HudWidget("btn_10", "10", 0.432f, 0.34f, 0.047f, 0.105f, 10),
    HudWidget("btn_15", "15", 0.498f, 0.34f, 0.047f, 0.105f, 15),
    HudWidget("btn_9", "9", 0.564f, 0.34f, 0.047f, 0.105f, 9),
    HudWidget("btn_11", "11", 0.431f, 0.62f, 0.045f, 0.105f, 11),
    HudWidget("btn_12", "12", 0.493f, 0.62f, 0.045f, 0.105f, 12),
    HudWidget("btn_17", "17", 0.554f, 0.62f, 0.045f, 0.105f, 17),
    HudWidget("btn_13", "13", 0.594f, 0.90f, 0.16f, 0.095f, 13)
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
