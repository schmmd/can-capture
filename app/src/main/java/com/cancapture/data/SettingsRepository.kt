package com.cancapture.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class ConnectionSettings(
    val host: String,
    val port: Int,
    val bus: String
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    private val keyHost = stringPreferencesKey("host")
    private val keyPort = intPreferencesKey("port")
    private val keyBus = stringPreferencesKey("bus")

    val settings: Flow<ConnectionSettings> = dataStore.data.map { prefs ->
        ConnectionSettings(
            host = prefs[keyHost] ?: DEFAULT_HOST,
            port = prefs[keyPort] ?: DEFAULT_PORT,
            bus = prefs[keyBus] ?: DEFAULT_BUS
        )
    }

    suspend fun update(host: String, port: Int, bus: String) {
        dataStore.edit { prefs ->
            prefs[keyHost] = host
            prefs[keyPort] = port
            prefs[keyBus] = bus
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.1.100"
        const val DEFAULT_PORT = 28600
        const val DEFAULT_BUS = "can0"
    }
}
