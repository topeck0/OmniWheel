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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.SettingsManager

data class HudWidget(
    val id: String,
    val label: String,
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var vJoyBtn: Int,
    var scale: Float = 1.0f,
    val isCircular: Boolean = false
)

data class EditorStateSnapshot(
    val widgets: List<HudWidget>
)

@Composable
fun HudEditorScreen(
    settings: SettingsManager,
    onBack: () -> Unit
) {
    // Initial exact layout matching ControllerScreen (18 buttons, steering wheel, 3 pedals)
    val initialWidgets = remember {
        mutableListOf<HudWidget>().apply {
            // Steering Wheel
            add(HudWidget("steering", "Steering", 200f, 100f, 220f, 220f, 0, isCircular = true))
            // Pedals
            add(HudWidget("clutch", "Clutch", 650f, 60f, 65f, 240f, 0))
            add(HudWidget("brake", "Brake", 730f, 60f, 65f, 240f, 0))
            add(HudWidget("gas", "Gas", 810f, 60f, 65f, 240f, 0))
            // Top row buttons (5, 6, 7, 18)
            add(HudWidget("btn_5", "5", 50f, 10f, 45f, 45f, 5))
            add(HudWidget("btn_6", "6", 100f, 10f, 45f, 45f, 6))
            add(HudWidget("btn_7", "7", 150f, 10f, 45f, 45f, 7))
            add(HudWidget("btn_18", "18", 200f, 10f, 50f, 45f, 18))
            // Button 4 & 8
            add(HudWidget("btn_4", "4", 20f, 150f, 45f, 45f, 4))
            add(HudWidget("btn_8", "8", 260f, 150f, 50f, 45f, 8))
            // Center-Right buttons (1, 3, 2, 10, 15, 9, 11, 12, 17, 13)
            add(HudWidget("btn_1", "1", 340f, 20f, 55f, 45f, 1))
            add(HudWidget("btn_3", "3", 400f, 20f, 55f, 45f, 3))
            add(HudWidget("btn_2", "2", 460f, 20f, 55f, 45f, 2))
            add(HudWidget("btn_10", "10", 340f, 75f, 50f, 45f, 10))
            add(HudWidget("btn_15", "15", 395f, 75f, 50f, 45f, 15))
            add(HudWidget("btn_9", "9", 450f, 75f, 50f, 45f, 9))
            add(HudWidget("btn_11", "11", 340f, 130f, 50f, 45f, 11))
            add(HudWidget("btn_12", "12", 395f, 130f, 50f, 45f, 12))
            add(HudWidget("btn_17", "17", 450f, 130f, 50f, 45f, 17))
            add(HudWidget("btn_13", "13", 370f, 185f, 110f, 40f, 13))
            // Button 14 (bottom-left)
            add(HudWidget("btn_14", "14", 50f, 260f, 45f, 45f, 14))
        }
    }

    val widgets = remember { mutableStateListOf<HudWidget>().apply { addAll(initialWidgets) } }
    
    // Undo / Redo history
    val undoStack = remember { mutableStateListOf<List<HudWidget>>() }
    val redoStack = remember { mutableStateListOf<List<HudWidget>>() }

    fun saveSnapshot() {
        undoStack.add(widgets.map { it.copy() })
        redoStack.clear()
    }

    var selectedWidget by remember { mutableStateOf<HudWidget?>(null) }
    var popupPos by remember { mutableStateOf(Offset(100f, 100f)) }
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
        // Canvas with draggable widgets
        Box(modifier = Modifier.fillMaxSize()) {
            widgets.forEach { widget ->
                val shape = if (widget.isCircular) CircleShape else RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .offset(x = widget.x.dp, y = widget.y.dp)
                        .size(width = (widget.width * widget.scale).dp, height = (widget.height * widget.scale).dp)
                        .border(2.dp, Color(0xFF00E5FF), shape)
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
                            // Smart positioning for popup window (left or right of screen)
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

        // Floating Island Top Toolbar (Horizontally draggable only, semi-transparent)
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
            // Undo button
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

            // Redo button
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

            // Add button (named after next vJoy button number)
            Button(
                onClick = {
                    saveSnapshot()
                    val nextBtnNum = widgets.count { it.id.startsWith("btn_") } + 1
                    widgets.add(HudWidget("btn_$nextBtnNum", "$nextBtnNum", 100f, 50f, 45f, 45f, nextBtnNum))
                    hasChanges = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("+ Add", fontSize = 12.sp, color = Color.White)
            }

            // Save button
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

        // Properties Popup Window (Movable anywhere, scrollable content, scale/width/height controls)
        selectedWidget?.let { widget ->
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
                                if (num != null) {
                                    saveSnapshot()
                                    widget.vJoyBtn = num
                                    widget.label = num.toString()
                                    hasChanges = true
                                }
                            },
                            label = { Text("Function / vJoy #", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Scale Slider
                    Text("Scale: ${"%.2f".format(widget.scale)}x", fontSize = 11.sp, color = Color.Gray)
                    Slider(
                        value = widget.scale,
                        onValueChange = {
                            widget.scale = it
                            hasChanges = true
                        },
                        valueRange = 0.5f..2.0f
                    )

                    // Width Slider
                    Text("Width: ${widget.width.toInt()}dp", fontSize = 11.sp, color = Color.Gray)
                    Slider(
                        value = widget.width,
                        onValueChange = {
                            widget.width = it
                            hasChanges = true
                        },
                        valueRange = 30f..300f
                    )

                    // Height Slider
                    Text("Height: ${widget.height.toInt()}dp", fontSize = 11.sp, color = Color.Gray)
                    Slider(
                        value = widget.height,
                        onValueChange = {
                            widget.height = it
                            hasChanges = true
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

        // Unsaved changes dialog
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
