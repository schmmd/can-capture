package com.cancapture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cancapture.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val settings by viewModel.settings.collectAsState()

    var host by remember { mutableStateOf(settings.host) }
    var portText by remember { mutableStateOf(settings.port.toString()) }
    var bus by remember { mutableStateOf(settings.bus) }
    var saved by remember { mutableStateOf(false) }

    // Sync local fields when settings load from DataStore.
    LaunchedEffect(settings) {
        host = settings.host
        portText = settings.port.toString()
        bus = settings.bus
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
        OutlinedTextField(
            value = bus,
            onValueChange = { bus = it; saved = false },
            label = { Text("Bus") },
            placeholder = { Text("can0") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                val port = portText.toIntOrNull() ?: 28600
                viewModel.save(host.trim(), port, bus.trim())
                saved = true
            },
            enabled = host.isNotBlank() && bus.isNotBlank() && portText.isNotBlank(),
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
}
