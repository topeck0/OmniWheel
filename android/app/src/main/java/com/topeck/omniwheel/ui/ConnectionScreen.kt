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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topeck.omniwheel.network.DiscoveryClient
import com.topeck.omniwheel.network.InputSender

@Composable
fun ConnectionScreen(
    discovery: DiscoveryClient,
    inputSender: InputSender,
    onConnected: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val devices = remember { mutableStateOf<List<DiscoveryClient.DiscoveredDevice>>(emptyList()) }
    val logs = remember { mutableStateListOf<String>() }
    var logText by remember { mutableStateOf("") }
    var connectingIp by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

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
                    text = "v0.6.1  |  Phone-as-Steering-Wheel",
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

        Spacer(Modifier.height(16.dp))

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
                        else "Scanning for PCs on WiFi...",
                fontSize = 13.sp,
                color = Color(0xFF888888)
            )
        }

        Spacer(Modifier.height(12.dp))

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
                    .weight(0.4f)
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
                        text = "Make sure OmniWheel PC is running\non the same WiFi network",
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
                    .weight(0.4f),
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

        // Log
        Text(
            text = "Log",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF444444)
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.25f)
                .background(Color(0xFF0A0A14), MaterialTheme.shapes.small)
                .padding(10.dp)
        ) {
            Text(
                text = logText.ifEmpty { "Waiting..." },
                fontSize = 9.sp,
                color = Color(0xFF555555),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                lineHeight = 13.sp
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