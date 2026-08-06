package com.topeck.omniwheel.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogsScreen(
    logs: List<String>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    BackHandler {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A))
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF080812))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color(0xFF888888),
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onBack() }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "System Logs",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE0E0E0)
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("OmniWheel Logs", logs.joinToString("\n"))
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) {
                Text("Copy", fontSize = 12.sp, color = Color.White)
            }
        }

        // Logs body
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text(
                text = if (logs.isEmpty()) "No logs yet..." else logs.joinToString("\n"),
                fontSize = 11.sp,
                color = Color(0xFFCCCCCC),
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }
    }
}
