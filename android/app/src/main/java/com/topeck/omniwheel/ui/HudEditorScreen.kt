package com.topeck.omniwheel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.BUTTON_DEF_H_DP
import com.topeck.omniwheel.BUTTON_DEF_W_DP
import com.topeck.omniwheel.HUD_REF_H
import com.topeck.omniwheel.HUD_REF_W
import com.topeck.omniwheel.HudSlot
import com.topeck.omniwheel.HudWidget
import com.topeck.omniwheel.R
import com.topeck.omniwheel.SettingsManager
import com.topeck.omniwheel.resetToTypeDefaults

@Composable
fun HudEditorScreen(
    settings: SettingsManager,
    onBack: () -> Unit
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWdp = config.screenWidthDp
    val screenHdp = config.screenHeightDp
    val screenWPx = with(density) { screenWdp.dp.toPx() }
    val screenHPx = with(density) { screenHdp.dp.toPx() }

    val initial = remember { settings.loadHudLayout() }
    val widgets = remember { mutableStateListOf<HudWidget>().apply { addAll(initial) } }

    val undoStack = remember { mutableStateListOf<List<HudWidget>>() }
    val redoStack = remember { mutableStateListOf<List<HudWidget>>() }

    fun snapshot(): List<HudWidget> = widgets.map { it.copy() }

    fun pushUndo() {
        undoStack.add(snapshot())
        redoStack.clear()
    }

    fun restore(list: List<HudWidget>) {
        widgets.clear()
        widgets.addAll(list)
    }

    var selectedWidget by remember { mutableStateOf<HudWidget?>(null) }
    var popupPos by remember { mutableStateOf(Offset(60f, 120f)) }
    var toolbarX by remember { mutableStateOf(60f) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }

    BackHandler {
        if (hasChanges) showUnsavedDialog = true else onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
    ) {
        Column(Modifier.fillMaxSize()) {
            // Reserve the same 26dp the game status bar uses so positions align
            Spacer(Modifier.height(26.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF131322))
            ) {
                val aw = maxWidth
                val ah = maxHeight
                val awPx = with(density) { aw.toPx() }
                val ahPx = with(density) { ah.toPx() }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, Color(0xFF23233C), RoundedCornerShape(8.dp))
                )

                widgets.forEach { widget ->
                    val idx = widgets.indexOfFirst { it.id == widget.id }
                    if (idx < 0) return@forEach
                    val shape = if (widget.isCircular) CircleShape else RoundedCornerShape(8.dp)
                    val selected = selectedWidget?.id == widget.id

                    HudSlot(widget, aw, ah) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(shape)
                                .background(
                                    when {
                                        selected -> Color(0x3300E5FF)
                                        widget.isSteering || widget.isPedal -> Color.Transparent
                                        else -> Color(0x221E1E36)
                                    }
                                )
                                .border(
                                    width = if (selected) 2.dp
                                    else if (widget.isSteering || widget.isPedal) 0.dp
                                    else 1.dp,
                                    color = if (selected) Color(0xFF00E5FF)
                                    else Color(0xFF2A2A44).copy(alpha = 0.5f),
                                    shape = shape
                                )
                                .pointerInput(widget.id) {
                                    detectDragGestures(
                                        onDragStart = { pushUndo() },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val cur = widgets.getOrNull(idx)
                                            if (cur != null) {
                                                // Move 1:1 with the finger, independent of scale.
                                                widgets[idx] = cur.copy(
                                                    cx = (cur.cx + dragAmount.x / awPx).coerceIn(0f, 1f),
                                                    cy = (cur.cy + dragAmount.y / ahPx).coerceIn(0f, 1f)
                                                )
                                                hasChanges = true
                                            }
                                        }
                                    )
                                }
                                .clickable {
                                    selectedWidget = widgets[idx]
                                    popupPos = if (widget.cx < 0.5f)
                                        Offset(screenWPx * 0.52f, 110f)
                                    else
                                        Offset(screenWPx * 0.04f, 110f)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                widget.isSteering -> Image(
                                    painter = painterResource(R.drawable.steering_wheel),
                                    contentDescription = "Steering Wheel",
                                    modifier = Modifier
                                        .fillMaxSize(0.85f)
                                        .aspectRatio(1f)
                                )

                                widget.isPedal -> PedalShape(
                                    label = widget.label,
                                    color = when (widget.id) {
                                        "gas" -> Color(0xFF22C55E)
                                        "brake" -> Color(0xFFEF4444)
                                        "clutch" -> Color(0xFFF59E0B)
                                        else -> Color(0xFF3A3A5E)
                                    },
                                    value = 0f,
                                    modifier = Modifier.fillMaxSize()
                                )

                                else -> Text(
                                    text = widget.label,
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating island toolbar (horizontally draggable only)
        Row(
            modifier = Modifier
                .offset(
                    x = with(density) { toolbarX.toDp() },
                    y = 12.dp
                )
                .alpha(0.85f)
                .background(Color(0xFF181828), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF2A2A44), RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        toolbarX = (toolbarX + dragAmount.x).coerceIn(10f, screenWPx - 260f)
                    }
                }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (undoStack.isNotEmpty()) {
                        redoStack.add(snapshot())
                        restore(undoStack.removeAt(undoStack.size - 1))
                        hasChanges = true
                    }
                },
                enabled = undoStack.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2A2A44),
                    disabledContainerColor = Color(0xFF151522)
                ),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("←", fontSize = 14.sp, color = if (undoStack.isNotEmpty()) Color.White else Color.Gray)
            }

            Button(
                onClick = {
                    if (redoStack.isNotEmpty()) {
                        undoStack.add(snapshot())
                        restore(redoStack.removeAt(redoStack.size - 1))
                        hasChanges = true
                    }
                },
                enabled = redoStack.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2A2A44),
                    disabledContainerColor = Color(0xFF151522)
                ),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("→", fontSize = 14.sp, color = if (redoStack.isNotEmpty()) Color.White else Color.Gray)
            }

            Button(
                onClick = {
                    pushUndo()
                    val nextBtnNum = widgets.count { it.id.startsWith("btn_") } + 1
                    widgets.add(
                        HudWidget(
                            "btn_$nextBtnNum", "$nextBtnNum",
                            0.3f, 0.5f,
                            BUTTON_DEF_W_DP / HUD_REF_W, BUTTON_DEF_H_DP / HUD_REF_H,
                            nextBtnNum
                        )
                    )
                    hasChanges = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("+ Add", fontSize = 12.sp, color = Color.White)
            }

            Button(
                onClick = {
                    settings.saveHudLayout(widgets.toList())
                    hasChanges = false
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("Save", fontSize = 12.sp, color = Color.White)
            }
        }

        // Properties popup
        selectedWidget?.let { widget ->
            val idx = widgets.indexOfFirst { it.id == widget.id }

            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { popupPos.x.toDp() },
                        y = with(density) { popupPos.y.toDp() }
                    )
                    .width(240.dp)
                    .heightIn(max = 320.dp)
                    .background(Color(0xFF141424), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF2A2A44), RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            popupPos = Offset(
                                (popupPos.x + dragAmount.x).coerceIn(0f, screenWPx - 260f),
                                (popupPos.y + dragAmount.y).coerceIn(0f, screenHPx - 360f)
                            )
                        }
                    }
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!widget.isSteering) {
                            Text(
                                text = "Properties: ${widget.label}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        } else {
                            Text(
                                text = "Scale (wheel)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Text(
                            text = "✕",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.clickable { selectedWidget = null }
                        )
                    }

                    if (widget.id.startsWith("btn_")) {
                        OutlinedTextField(
                            value = widget.vJoyBtn.toString(),
                            onValueChange = {
                                val num = it.toIntOrNull()
                                if (num != null && idx >= 0) {
                                    pushUndo()
                                    val updated = widget.copy(vJoyBtn = num, label = num.toString())
                                    widgets[idx] = updated
                                    selectedWidget = updated
                                    hasChanges = true
                                }
                            },
                            label = { Text("Function / vJoy #", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Infinite scale drag handle: drag right = bigger, left = smaller
                    Text("Scale: ${"%.2f".format(widget.scale)}x (drag right/left)", fontSize = 11.sp, color = Color.Gray)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .background(Color(0xFF1E1E36), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF6366F1), RoundedCornerShape(6.dp))
                            .pointerInput(widget.id) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val cur = widgets.getOrNull(idx)
                                    if (cur != null) {
                                        val newScale = (cur.scale + dragAmount.x * 0.005f).coerceIn(0.05f, 20f)
                                        val updated = cur.copy(scale = newScale)
                                        widgets[idx] = updated
                                        selectedWidget = updated
                                        hasChanges = true
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⟷ Drag to Scale ⟷",
                            fontSize = 11.sp,
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Width & height hidden for steering wheels (circular, scale only).
                    // Pedals use a wide width range: 300dp .. 1000dp.
                    if (!widget.isSteering) {
                        val wMin = if (widget.isPedal) 300f / HUD_REF_W else 0.02f
                        val wMax = if (widget.isPedal) 1000f / HUD_REF_W else 0.5f
                        Text("Width: ${(widget.wFrac * HUD_REF_W).toInt()}dp", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = widget.wFrac.coerceIn(wMin, wMax),
                            onValueChange = { nw ->
                                if (idx >= 0) {
                                    val updated = widget.copy(wFrac = nw)
                                    widgets[idx] = updated
                                    selectedWidget = updated
                                    hasChanges = true
                                }
                            },
                            valueRange = wMin..wMax
                        )

                        Text("Height: ${(widget.hFrac * HUD_REF_H).toInt()}dp", fontSize = 11.sp, color = Color.Gray)
                        Slider(
                            value = widget.hFrac.coerceIn(0.02f, 0.9f),
                            onValueChange = { nh ->
                                if (idx >= 0) {
                                    val updated = widget.copy(hFrac = nh)
                                    widgets[idx] = updated
                                    selectedWidget = updated
                                    hasChanges = true
                                }
                            },
                            valueRange = 0.02f..0.9f
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // Reset properties to this widget type's defaults (before Delete).
                    Button(
                        onClick = {
                            pushUndo()
                            val updated = widget.resetToTypeDefaults()
                            widgets[idx] = updated
                            selectedWidget = updated
                            hasChanges = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                    ) {
                        Text(
                            text = when {
                                widget.isSteering -> "Reset Scale"
                                widget.isPedal -> "Reset Pedal"
                                else -> "Reset Button"
                            },
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }

                    if (!widget.isSteering) {
                        Button(
                            onClick = {
                                pushUndo()
                                widgets.removeAll { it.id == widget.id }
                                selectedWidget = null
                                hasChanges = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                        ) {
                            Text("Delete", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (showUnsavedDialog) {
            AlertDialog(
                onDismissRequest = { showUnsavedDialog = false },
                title = { Text("Unsaved Changes") },
                text = { Text("You have unsaved changes. Do you want to save them?") },
                confirmButton = {
                    TextButton(onClick = {
                        settings.saveHudLayout(widgets.toList())
                        showUnsavedDialog = false
                        hasChanges = false
                        onBack()
                    }) { Text("Save") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            showUnsavedDialog = false
                            onBack()
                        }) { Text("Discard") }
                        TextButton(onClick = { showUnsavedDialog = false }) { Text("Cancel") }
                    }
                }
            )
        }
    }
}
