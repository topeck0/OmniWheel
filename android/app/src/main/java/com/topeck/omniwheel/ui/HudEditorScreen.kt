package com.topeck.omniwheel.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.SettingsManager

data class HudWidget(
    val id: String,
    var label: String,
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var vJoyBtn: Int,
    var scale: Float = 1.0f,
    val isCircular: Boolean = false
)

@Composable
fun HudEditorScreen(
    settings: SettingsManager,
    onBack: () -> Unit
) {
    // Exact initial layout matching ControllerScreen proportions
    val initialWidgets = remember {
        listOf(
            HudWidget("steering", "Steering", 180f, 100f, 180f, 180f, 0, isCircular = true),
            HudWidget("clutch", "Clutch", 640f, 40f, 60f, 220f, 0),
            HudWidget("brake", "Brake", 710f, 40f, 60f, 220f, 0),
            HudWidget("gas", "Gas", 780f, 40f, 60f, 220f, 0),
            HudWidget("btn_5", "5", 40f, 10f, 40f, 40f, 5),
            HudWidget("btn_6", "6", 85f, 10f, 40f, 40f, 6),
            HudWidget("btn_7", "7", 130f, 10f, 40f, 40f, 7),
            HudWidget("btn_18", "18", 175f, 10f, 45f, 40f, 18),
            HudWidget("btn_4", "4", 10f, 130f, 40f, 40f, 4),
            HudWidget("btn_8", "8", 225f, 130f, 45f, 40f, 8),
            HudWidget("btn_1", "1", 300f, 15f, 50f, 40f, 1),
            HudWidget("btn_3", "3", 355f, 15f, 50f, 40f, 3),
            HudWidget("btn_2", "2", 410f, 15f, 50f, 40f, 2),
            HudWidget("btn_10", "10", 300f, 65f, 45f, 40f, 10),
            HudWidget("btn_15", "15", 350f, 65f, 45f, 40f, 15),
            HudWidget("btn_9", "9", 400f, 65f, 45f, 40f, 9),
            HudWidget("btn_11", "11", 300f, 115f, 45f, 40f, 11),
            HudWidget("btn_12", "12", 350f, 115f, 45f, 40f, 12),
            HudWidget("btn_17", "17", 400f, 115f, 45f, 40f, 17),
            HudWidget("btn_13", "13", 320f, 165f, 100f, 35f, 13),
            HudWidget("btn_14", "14", 40f, 240f, 40f, 40f, 14)
        )
    }

    val widgets = remember { mutableStateListOf<HudWidget>().apply { addAll(initialWidgets) } }
    
    val undoStack = remember { mutableStateListOf<List<HudWidget>>() }
    val redoStack = remember { mutableStateListOf<List<HudWidget>>() }

    fun saveSnapshot() {
        undoStack.add(widgets.map { it.copy() })
        redoStack.clear()
    }

    var selectedWidget by remember { mutableStateOf<HudWidget?>(null) }
    var popupPos by remember { mutableStateOf(Offset(50f, 50f)) }
    var toolbarX by remember { mutableStateOf(100f) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }

    BackHandler {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            widgets.forEachIndexed { index, widget ->
                val shape = if (widget.isCircular) CircleShape else RoundedCornerShape(8.dp)
                val isSelected = selectedWidget?.id == widget.id
                Box(
                    modifier = Modifier
                        .offset(x = widget.x.dp, y = widget.y.dp)
                        .size(width = (widget.width * widget.scale).dp, height = (widget.height * widget.scale).dp)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF2A2A44).copy(alpha = 0.5f),
                            shape = shape
                        )
                        .clip(shape)
                        .background(Color(0xFF1E1E36).copy(alpha = 0.6f))
                        .pointerInput(widget.id) {
                            detectDragGestures(
                                onDragStart = { saveSnapshot() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    widget.x += dragAmount.x / density
                                    widget.y += dragAmount.y / density
                                    hasChanges = true
                                }
                            )
                        }
                        .clickable {
                            selectedWidget = widget
                            popupPos = if (widget.x < 400f) Offset(500f, 50f) else Offset(50f, 50f)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = widget.label,
                        fontSize = 11.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Floating Island Top Toolbar
        Row(
            modifier = Modifier
                .offset(x = toolbarX.dp, y = 12.dp)
                .alpha(0.85f)
                .background(Color(0xFF181828), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF2A2A44), RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        toolbarX = (toolbarX + dragAmount.x / density).coerceIn(10f, 500f)
                    }
                }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (undoStack.isNotEmpty()) {
                        redoStack.add(widgets.map { it.copy() })
                        val prev = undoStack.removeAt(undoStack.size - 1)
                        widgets.clear()
                        widgets.addAll(prev)
                        hasChanges = true
                    }
                },
                enabled = undoStack.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A44), disabledContainerColor = Color(0xFF151522)),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("←", fontSize = 14.sp, color = if (undoStack.isNotEmpty()) Color.White else Color.Gray)
            }

            Button(
                onClick = {
                    if (redoStack.isNotEmpty()) {
                        undoStack.add(widgets.map { it.copy() })
                        val next = redoStack.removeAt(redoStack.size - 1)
                        widgets.clear()
                        widgets.addAll(next)
                        hasChanges = true
                    }
                },
                enabled = redoStack.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A44), disabledContainerColor = Color(0xFF151522)),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("→", fontSize = 14.sp, color = if (redoStack.isNotEmpty()) Color.White else Color.Gray)
            }

            Button(
                onClick = {
                    saveSnapshot()
                    val nextBtnNum = widgets.count { it.id.startsWith("btn_") } + 1
                    widgets.add(HudWidget("btn_$nextBtnNum", "$nextBtnNum", 100f, 50f, 40f, 40f, nextBtnNum))
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

        // Properties Popup Window with Live Updates
        selectedWidget?.let { widget ->
            val index = widgets.indexOfFirst { it.id == widget.id }
            Box(
                modifier = Modifier
                    .offset(x = popupPos.x.dp, y = popupPos.y.dp)
                    .width(240.dp)
                    .heightIn(max = 320.dp)
                    .background(Color(0xFF141424), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF2A2A44), RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            popupPos = Offset(popupPos.x + dragAmount.x / density, popupPos.y + dragAmount.y / density)
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
                        Text(text = "Properties: ${widget.label}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                                if (num != null && index >= 0) {
                                    saveSnapshot()
                                    val updated = widget.copy(vJoyBtn = num, label = num.toString())
                                    widgets[index] = updated
                                    selectedWidget = updated
                                    hasChanges = true
                                }
                            },
                            label = { Text("Function / vJoy #", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Text("Scale: ${"%.2f".format(widget.scale)}x", fontSize = 11.sp, color = Color.Gray)
                    Slider(
                        value = widget.scale,
                        onValueChange = { newScale ->
                            if (index >= 0) {
                                val updated = widget.copy(scale = newScale)
                                widgets[index] = updated
                                selectedWidget = updated
                                hasChanges = true
                            }
                        },
                        valueRange = 0.5f..2.0f
                    )

                    Text("Width: ${widget.width.toInt()}dp", fontSize = 11.sp, color = Color.Gray)
                    Slider(
                        value = widget.width,
                        onValueChange = { newW ->
                            if (index >= 0) {
                                val updated = widget.copy(width = newW)
                                widgets[index] = updated
                                selectedWidget = updated
                                hasChanges = true
                            }
                        },
                        valueRange = 30f..300f
                    )

                    Text("Height: ${widget.height.toInt()}dp", fontSize = 11.sp, color = Color.Gray)
                    Slider(
                        value = widget.height,
                        onValueChange = { newH ->
                            if (index >= 0) {
                                val updated = widget.copy(height = newH)
                                widgets[index] = updated
                                selectedWidget = updated
                                hasChanges = true
                            }
                        },
                        valueRange = 30f..300f
                    )

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = {
                            saveSnapshot()
                            widgets.remove(widget)
                            selectedWidget = null
                            hasChanges = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    ) {
                        Text("Delete", color = Color.White, fontSize = 12.sp)
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
