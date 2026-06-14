package com.cancapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cancapture.data.ChannelConfig
import com.cancapture.data.ChannelConfigJson
import com.cancapture.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val settings by viewModel.settings.collectAsState()

    var host by remember { mutableStateOf(settings.host) }
    var portText by remember { mutableStateOf(settings.port.toString()) }
    val channels = remember { mutableStateListOf<ChannelConfig>().apply { addAll(settings.channels) } }
    var saved by remember { mutableStateOf(false) }
    var pollEditorFor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(settings) {
        host = settings.host
        portText = settings.port.toString()
        channels.clear()
        channels.addAll(settings.channels)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text(
            "socketcand connection used for all captures.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it; saved = false },
            label = { Text("Host") },
            placeholder = { Text("192.168.1.100") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { v -> portText = v.filter { it.isDigit() }.take(5); saved = false },
            label = { Text("Port") },
            placeholder = { Text("28600") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "Interfaces",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Each interface is recorded as a separate channel in the same .asc file. " +
                "Use Polling to send periodic UDS requests on a diagnostic bus.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        channels.forEachIndexed { index, cfg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = cfg.bus,
                            onValueChange = { v ->
                                channels[index] = when (val c = cfg) {
                                    is ChannelConfig.Passive -> c.copy(bus = v)
                                    is ChannelConfig.UdsPoll -> c.copy(bus = v)
                                }
                                saved = false
                            },
                            label = { Text("Channel ${index + 1}") },
                            placeholder = { Text("can${index}") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                channels.removeAt(index)
                                saved = false
                            },
                            enabled = channels.size > 1
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove interface")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = channelSummary(cfg),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (cfg is ChannelConfig.UdsPoll)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { pollEditorFor = index }) {
                            Text(if (cfg is ChannelConfig.UdsPoll) "Edit polling" else "Set polling")
                        }
                        if (cfg is ChannelConfig.UdsPoll) {
                            TextButton(onClick = {
                                channels[index] = ChannelConfig.Passive(cfg.bus)
                                saved = false
                            }) { Text("Clear") }
                        }
                    }
                }
            }
        }

        TextButton(
            onClick = {
                channels.add(ChannelConfig.Passive(""))
                saved = false
            }
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("  Add interface")
        }

        val cleaned = channels.mapNotNull { c ->
            val name = c.bus.trim()
            if (name.isEmpty()) return@mapNotNull null
            when (c) {
                is ChannelConfig.Passive -> c.copy(bus = name)
                is ChannelConfig.UdsPoll -> c.copy(bus = name)
            }
        }
        Button(
            onClick = {
                val port = portText.toIntOrNull() ?: 28600
                viewModel.save(host.trim(), port, cleaned)
                saved = true
            },
            enabled = host.isNotBlank() && portText.isNotBlank() && cleaned.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }

        if (saved) {
            Text(
                "Saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    val editIndex = pollEditorFor
    if (editIndex != null && editIndex in channels.indices) {
        PollEditorDialog(
            current = channels[editIndex],
            onDismiss = { pollEditorFor = null },
            onSave = { updated ->
                channels[editIndex] = updated
                saved = false
                pollEditorFor = null
            },
        )
    }
}

@Composable
private fun PollEditorDialog(
    current: ChannelConfig,
    onDismiss: () -> Unit,
    onSave: (ChannelConfig) -> Unit,
) {
    val initial = remember(current) {
        when (current) {
            is ChannelConfig.UdsPoll -> ChannelConfigJson.encodeUdsPoll(current)
            is ChannelConfig.Passive -> PLACEHOLDER_JSON
        }
    }
    var text by remember(current) { mutableStateOf(initial) }
    var errorText by remember(current) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("UDS polling for ${current.bus.ifBlank { "channel" }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a JSON profile. Polling sends each request periodically and " +
                        "records the responses into the same .asc file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; errorText = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 360.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("JSON") },
                )
                val err = errorText
                if (err != null) {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val parsed = ChannelConfigJson.parseUdsPoll(current.bus, text)
                    onSave(parsed)
                } catch (e: Exception) {
                    errorText = e.message ?: e.javaClass.simpleName
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun channelSummary(cfg: ChannelConfig): String = when (cfg) {
    is ChannelConfig.Passive -> "Passive (RX only)"
    is ChannelConfig.UdsPoll -> {
        val hz = if (cfg.periodMs > 0) 1000.0 / cfg.periodMs else 0.0
        "UDS poll: ${cfg.entries.size} request(s) @ ${"%.2f".format(hz)} Hz, " +
            "tx 0x${"%X".format(cfg.txId)} / rx 0x${"%X".format(cfg.rxId)}"
    }
}

private const val PLACEHOLDER_JSON = """{
  "txId": "0x7E0",
  "rxId": "0x7E8",
  "extended": false,
  "periodMs": 1000,
  "timeoutMs": 1000,
  "polls": [
    { "service": "0x22", "data": "0xF180" },
    { "service": "0x22", "data": "0xF181" }
  ]
}"""
