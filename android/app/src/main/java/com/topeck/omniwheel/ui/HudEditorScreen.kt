package com.topeck.omniwheel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.SettingsManager

data class WidgetConfig(
    val id: String,
    val name: String,
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var vJoyBtn: Int,
    var scale: Float = 1f
)

@Composable
fun HudEditorScreen(
    settings: SettingsManager,
    onBack: () -> Unit
) {
    val widgets = remember {
        mutableStateListOf(
            WidgetConfig("steering", "Steering Wheel", 50f, 150f, 200f, 200f, 0),
            WidgetConfig("gas", "Gas Pedal", 600f, 100f, 60f, 220f, 0),
            WidgetConfig("brake", "Brake Pedal", 520f, 100f, 60f, 220f, 0),
            WidgetConfig("clutch", "Clutch Pedal", 440f, 100f, 60f, 220f, 0),
            WidgetConfig("btn1", "Button 1", 300f, 50f, 50f, 50f, 1),
            WidgetConfig("btn2", "Button 2", 360f, 50f, 50f, 50f, 2)
        )
    }

    var selectedWidget by remember { mutableStateOf<WidgetConfig?>(null) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
    ) {
        // Canvas with draggable widgets having cyan outline
        Box(modifier = Modifier.fillMaxSize()) {
            widgets.forEach { widget ->
                Box(
                    modifier = Modifier
                        .offset(x = widget.x.dp, y = widget.y.dp)
                        .size(width = (widget.width * widget.scale).dp, height = (widget.height * widget.scale).dp)
                        .border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E1E36).copy(alpha = 0.6f))
                        .pointerInput(widget.id) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                widget.x += dragAmount.x / density
                                widget.y += dragAmount.y / density
                                hasChanges = true
                            }
                        }
                        .clickable { selectedWidget = widget },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = widget.name,
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Floating Island Top Toolbar
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
                .background(Color(0xFF181828), RoundedCornerShape(24.dp))
                .border(1.dp, Color(0xFF2A2A44), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    widgets.add(WidgetConfig("btn_${System.currentTimeMillis()}", "New Button", 100f, 50f, 50f, 50f, 3))
                    hasChanges = true
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Text("+ Add", fontSize = 12.sp, color = Color.White)
            }
            Button(
                onClick = {
                    if (hasChanges) showUnsavedDialog = true else onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A44)),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Text("Back", fontSize = 12.sp, color = Color.White)
            }
            Button(
                onClick = {
                    hasChanges = false
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Text("Save", fontSize = 12.sp, color = Color.White)
            }
        }

        // Properties Popup Menu (Vertical)
        selectedWidget?.let { widget ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(260.dp)
                    .background(Color(0xFF141424))
                    .border(1.dp, Color(0xFF2A2A44))
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Properties: ${widget.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            text = "X",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.clickable { selectedWidget = null }
                        )
                    }

                    if (widget.id.startsWith("btn")) {
                        OutlinedTextField(
                            value = widget.vJoyBtn.toString(),
                            onValueChange = {
                                widget.vJoyBtn = it.toIntOrNull() ?: widget.vJoyBtn
                                hasChanges = true
                            },
                            label = { Text("vJoy Button #", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Text("Scale: ${"%.2f".format(widget.scale)}x", fontSize = 12.sp, color = Color.Gray)
                    Slider(
                        value = widget.scale,
                        onValueChange = {
                            widget.scale = it
                            hasChanges = true
                        },
                        valueRange = 0.5f..2.0f
                    )

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = {
                            widgets.remove(widget)
                            selectedWidget = null
                            hasChanges = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Widget", color = Color.White)
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
