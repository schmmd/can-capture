package com.cancapture

import android.app.Application
import com.cancapture.data.CaptureRepository
import com.cancapture.data.SettingsRepository
import com.cancapture.data.settingsDataStore

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            settingsRepository = SettingsRepository(settingsDataStore),
            captureRepository = CaptureRepository(this)
        )
    }
}

data class AppContainer(
    val settingsRepository: SettingsRepository,
    val captureRepository: CaptureRepository
)
