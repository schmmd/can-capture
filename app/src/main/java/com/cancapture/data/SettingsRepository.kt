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
    val channels: List<ChannelConfig>,
) {
    val buses: List<String> get() = channels.map { it.bus }
}

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    private val keyHost = stringPreferencesKey("host")
    private val keyPort = intPreferencesKey("port")
    private val keyBus = stringPreferencesKey("bus")
    private val keyBuses = stringPreferencesKey("buses")
    private val keyChannelsJson = stringPreferencesKey("channels_json")

    val settings: Flow<ConnectionSettings> = dataStore.data.map { prefs ->
        val channels = prefs[keyChannelsJson]
            ?.let { ChannelConfigJson.decodeChannels(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: legacyChannels(prefs)
        ConnectionSettings(
            host = prefs[keyHost] ?: DEFAULT_HOST,
            port = prefs[keyPort] ?: DEFAULT_PORT,
            channels = channels,
        )
    }

    suspend fun update(host: String, port: Int, channels: List<ChannelConfig>) {
        dataStore.edit { prefs ->
            prefs[keyHost] = host
            prefs[keyPort] = port
            prefs[keyChannelsJson] = ChannelConfigJson.encodeChannels(channels)
            prefs.remove(keyBuses)
            prefs.remove(keyBus)
        }
    }

    private fun legacyChannels(prefs: Preferences): List<ChannelConfig> {
        val fromBuses = prefs[keyBuses]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
        if (fromBuses != null) return fromBuses.map { ChannelConfig.Passive(it) }
        val singleBus = prefs[keyBus]?.takeIf { it.isNotBlank() }
        if (singleBus != null) return listOf(ChannelConfig.Passive(singleBus))
        return listOf(ChannelConfig.Passive(DEFAULT_BUS))
    }

    companion object {
        const val DEFAULT_HOST = "192.168.1.100"
        const val DEFAULT_PORT = 28600
        const val DEFAULT_BUS = "can0"
    }
}
