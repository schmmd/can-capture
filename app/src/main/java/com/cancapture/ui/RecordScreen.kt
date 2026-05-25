package com.cancapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cancapture.viewmodel.RecordUiState
import com.cancapture.viewmodel.RecordViewModel

@Composable
fun RecordScreen(viewModel: RecordViewModel = viewModel(factory = RecordViewModel.Factory)) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "CAN Capture",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formatElapsed(stateElapsed(state)),
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                color = when (state) {
                    is RecordUiState.Recording -> MaterialTheme.colorScheme.primary
                    is RecordUiState.Connecting -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Status: ${statusLabel(state)}", style = MaterialTheme.typography.titleMedium)
                val frames = (state as? RecordUiState.Recording)?.frameCount ?: 0
                Text("Frames: $frames")
                val lastId = (state as? RecordUiState.Recording)?.lastFrameId
                if (!lastId.isNullOrEmpty()) {
                    Text("Last ID: 0x$lastId", fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        when (state) {
            is RecordUiState.Idle, is RecordUiState.Error -> {
                Button(
                    onClick = { viewModel.start() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Start", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }
            is RecordUiState.Connecting -> {
                Button(
                    onClick = { viewModel.stop() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                    enabled = false
                ) { Text("Connecting…") }
            }
            is RecordUiState.Recording -> {
                Button(
                    onClick = { viewModel.stop() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Stop", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
            is RecordUiState.PendingSave -> {
                // Save dialog rendered below
            }
        }
    }

    // Error dialog
    val err = state as? RecordUiState.Error
    if (err != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Capture error") },
            text = { Text(err.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
            }
        )
    }

    // Save dialog
    val pending = state as? RecordUiState.PendingSave
    if (pending != null) {
        var name by remember { mutableStateOf(defaultName()) }
        AlertDialog(
            onDismissRequest = { /* require user choice */ },
            title = { Text("Save capture") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pending.frameCount} frames, ${formatElapsed(pending.durationMs)}")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.saveAs(name.ifBlank { defaultName() }) },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.discardPending() }) { Text("Discard") }
            }
        )
    }
}

private fun stateElapsed(state: RecordUiState): Long = when (state) {
    is RecordUiState.Recording -> state.elapsedMs
    is RecordUiState.PendingSave -> state.durationMs
    else -> 0L
}

private fun statusLabel(state: RecordUiState): String = when (state) {
    is RecordUiState.Idle -> "Idle"
    is RecordUiState.Connecting -> "Connecting to ${state.host}:${state.port} (${state.bus})"
    is RecordUiState.Recording -> "Recording"
    is RecordUiState.PendingSave -> "Stopped — name to save"
    is RecordUiState.Error -> "Error"
}

internal fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val tenths = (ms % 1000) / 100
    return if (h > 0) "%d:%02d:%02d.%d".format(h, m, s, tenths)
    else "%02d:%02d.%d".format(m, s, tenths)
}

private fun defaultName(): String {
    val now = java.time.LocalDateTime.now()
    return "capture-%04d%02d%02d-%02d%02d%02d".format(
        now.year, now.monthValue, now.dayOfMonth,
        now.hour, now.minute, now.second
    )
}
