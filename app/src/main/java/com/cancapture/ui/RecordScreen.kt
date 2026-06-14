package com.cancapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cancapture.viewmodel.BusMode
import com.cancapture.viewmodel.BusPhase
import com.cancapture.viewmodel.BusStatus
import com.cancapture.viewmodel.RecordUiState
import com.cancapture.viewmodel.RecordViewModel

@Composable
fun RecordScreen(viewModel: RecordViewModel = viewModel(factory = RecordViewModel.Factory)) {
    val state by viewModel.state.collectAsState()

    val view = LocalView.current
    val keepScreenOn = state is RecordUiState.Recording
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

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
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status: ${statusLabel(state)}", style = MaterialTheme.typography.titleMedium)
                val recording = state as? RecordUiState.Recording
                if (recording != null) {
                    Text("Total frames: ${recording.frameCount}")
                    recording.buses.forEachIndexed { index, bus ->
                        BusRow(channel = index + 1, bus = bus)
                    }
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

@Composable
private fun BusRow(channel: Int, bus: BusStatus) {
    val phaseColor = when (bus.phase) {
        BusPhase.Active -> MaterialTheme.colorScheme.primary
        BusPhase.Listening -> MaterialTheme.colorScheme.secondary
        BusPhase.Connecting -> MaterialTheme.colorScheme.secondary
        BusPhase.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
        BusPhase.Errored -> Color(0xFFD32F2F)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "ch$channel ${bus.name}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = busPhaseLabel(bus),
            style = MaterialTheme.typography.bodySmall,
            color = phaseColor
        )
        Text(
            text = "${bus.frameCount}",
            style = MaterialTheme.typography.bodySmall
        )
    }
    val lastId = bus.lastFrameId
    if (lastId != null) {
        Text(
            text = "    last: 0x$lastId",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (bus.mode == BusMode.UdsPoll) {
        Text(
            text = "    polls: ok=${bus.polls} err=${bus.pollErrors}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        val pollErr = bus.lastPollError
        if (pollErr != null) {
            Text(
                text = "    last poll error: $pollErr",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD32F2F),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    val errMsg = bus.errorMessage
    if (errMsg != null && bus.phase == BusPhase.Errored) {
        Text(
            text = "    $errMsg",
            style = MaterialTheme.typography.bodySmall,
            color = phaseColor,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun busPhaseLabel(bus: BusStatus): String = when (bus.phase) {
    BusPhase.Connecting -> "Connecting…"
    BusPhase.Listening -> "Listening (no frames)"
    BusPhase.Active -> "Active"
    BusPhase.Disconnected -> "Disconnected"
    BusPhase.Errored -> "Errored"
}

private fun stateElapsed(state: RecordUiState): Long = when (state) {
    is RecordUiState.Recording -> state.elapsedMs
    is RecordUiState.PendingSave -> state.durationMs
    else -> 0L
}

private fun statusLabel(state: RecordUiState): String = when (state) {
    is RecordUiState.Idle -> "Idle"
    is RecordUiState.Recording -> {
        val open = state.buses.count { it.phase == BusPhase.Active || it.phase == BusPhase.Listening }
        val total = state.buses.size
        when {
            state.buses.any { it.phase == BusPhase.Connecting } -> "Recording ($open / $total open)"
            open == total -> "Recording"
            else -> "Recording ($open / $total open)"
        }
    }
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
