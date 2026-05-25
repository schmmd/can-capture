package com.cancapture.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cancapture.App
import com.cancapture.data.ConnectionSettings
import com.cancapture.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    application: Application,
    private val repo: SettingsRepository
) : AndroidViewModel(application) {

    val settings: StateFlow<ConnectionSettings> = repo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ConnectionSettings(
            host = SettingsRepository.DEFAULT_HOST,
            port = SettingsRepository.DEFAULT_PORT,
            bus = SettingsRepository.DEFAULT_BUS
        )
    )

    fun save(host: String, port: Int, bus: String) {
        viewModelScope.launch { repo.update(host, port, bus) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App
                SettingsViewModel(app, app.container.settingsRepository)
            }
        }
    }
}
