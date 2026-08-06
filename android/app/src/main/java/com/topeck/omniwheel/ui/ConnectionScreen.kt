package com.topeck.omniwheel.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.SettingsManager
import com.topeck.omniwheel.network.DiscoveryClient
import com.topeck.omniwheel.network.InputSender

@Composable
fun ConnectionScreen(
    discovery: DiscoveryClient,
    inputSender: InputSender,
    settings: SettingsManager,
    onConnected: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHudEditor: () -> Unit
) {
    val devices = remember { mutableStateOf<List<DiscoveryClient.DiscoveredDevice>>(emptyList()) }
    val logs = remember { mutableStateListOf<String>() }
    var logText by remember { mutableStateOf("") }
    var connectingIp by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // Manual IP entry — pre-filled from last used IP
    var manualIp by remember { mutableStateOf(settings.lastUsedIp) }
    var isDirectConnecting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        discovery.onDeviceFound = { device ->
            devices.value = discovery.getDiscoveredDevices()
        }
        discovery.onLog = { msg ->
            synchronized(logs) {
                logs.add("[D] $msg")
                if (logs.size > 50) logs.removeFirst()
                logText = logs.joinToString("\n")
            }
        }
        inputSender.onLog = { msg ->
            synchronized(logs) {
                logs.add("[I] $msg")
                if (logs.size > 50) logs.removeFirst()
                logText = logs.joinToString("\n")
            }
        }
        discovery.start()
    }

    DisposableEffect(Unit) {
        onDispose {
            discovery.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
            .padding(20.dp)
    ) {
        // Title row with settings button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "OmniWheel",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6366F1)
                )
                Text(
                    text = "v0.8.9-alpha  |  Phone-as-Steering-Wheel",
                    fontSize = 12.sp,
                    color = Color(0xFF555555)
                )
            }

            // Settings gear button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF1E1E36), MaterialTheme.shapes.medium)
                    .clickable { onOpenSettings() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "\u2699",  // gear symbol
                    fontSize = 20.sp,
                    color = Color(0xFF888888)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== MANUAL IP QUICK CONNECT =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                label = { Text("IP Address", fontSize = 11.sp, color = Color(0xFF6A6A8A)) },
                placeholder = { Text("192.168.1.100", fontSize = 11.sp, color = Color(0xFF3A3A5A)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 14.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6366F1),
                    unfocusedBorderColor = Color(0xFF2A2A44),
                    cursorColor = Color(0xFF6366F1),
                    focusedLabelColor = Color(0xFF6366F1),
                    unfocusedContainerColor = Color(0xFF12122A),
                    focusedContainerColor = Color(0xFF14142E),
                ),
                modifier = Modifier.weight(1f).height(56.dp)
            )

            Button(
                onClick = {
                    val ip = manualIp.trim()
                    if (ip.isBlank() || !ip.contains(".")) {
                        errorMsg = "Enter a valid IP address"
                        return@Button
                    }
                    errorMsg = null
                    isDirectConnecting = true
                    settings.lastUsedIp = ip
                    inputSender.disconnect()
                    inputSender.connectDirect(
                        ip,
                        onReady = {
                            isDirectConnecting = false
                            onConnected(ip)
                        },
                        onError = { err ->
                            isDirectConnecting = false
                            errorMsg = "Direct connect failed: $err"
                        }
                    )
                },
                enabled = !isDirectConnecting && manualIp.trim().isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6366F1),
                    disabledContainerColor = Color(0xFF1E1E36)
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = if (isDirectConnecting) "..." else "GO",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDirectConnecting) Color(0xFF555555) else Color.White
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Scanning indicator
        val pulseAlpha by rememberInfiniteTransition().animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse
            )
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\u25CF",
                fontSize = 12.sp,
                color = Color(0xFF6366F1).copy(alpha = pulseAlpha),
                modifier = Modifier.alpha(pulseAlpha)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (connectingIp != null) "Connecting to $connectingIp..."
                        else if (isDirectConnecting) "Direct connecting..."
                        else "Scanning for PCs on WiFi...",
                fontSize = 13.sp,
                color = Color(0xFF888888)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Error message
        errorMsg?.let { err ->
            Text(
                text = err,
                fontSize = 11.sp,
                color = Color(0xFFEF4444),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Device list
        if (devices.value.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.35f)
                    .background(Color(0xFF14142A), MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "(  )",
                        fontSize = 32.sp,
                        color = Color(0xFF333355)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No PCs found",
                        fontSize = 14.sp,
                        color = Color(0xFF555555),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Use the IP field above to connect manually\nor make sure OmniWheel PC is running\non the same WiFi network",
                        fontSize = 11.sp,
                        color = Color(0xFF3A3A5A),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.35f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices.value, key = { it.ipAddress }) { device ->
                    DeviceCard(
                        device = device,
                        isConnecting = connectingIp == device.ipAddress,
                        onClick = {
                            if (connectingIp != null) return@DeviceCard
                            errorMsg = null
                            connectingIp = device.ipAddress
                            settings.lastUsedIp = device.ipAddress
                            inputSender.disconnect()
                            inputSender.connect(device.ipAddress,
                                onReady = {
                                    connectingIp = null
                                    onConnected(device.ipAddress)
                                },
                                onError = { err ->
                                    connectingIp = null
                                    errorMsg = "Connection failed: $err"
                                }
                            )
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // HUD Editor Button
        Button(
            onClick = { onOpenHudEditor() },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E36)),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = "HUD Editor",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00E5FF)
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: DiscoveryClient.DiscoveredDevice,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        when {
            isConnecting -> Color(0xFF1A1A3E)
            else -> Color(0xFF16162E)
        },
        label = "bg"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PC",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF6366F1),
                modifier = Modifier
                    .background(Color(0xFF1E1E3E), MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE0E0E0)
                )
                Text(
                    text = device.ipAddress,
                    fontSize = 11.sp,
                    color = Color(0xFF6366F1)
                )
            }

            if (isConnecting) {
                Text(
                    text = "CONNECTING...",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF59E0B)
                )
            } else {
                Text(
                    text = "CONNECT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF22C55E)
                )
            }
        }
    }
}