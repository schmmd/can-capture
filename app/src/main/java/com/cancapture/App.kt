package com.cancapture

import android.app.Application
import com.cancapture.data.CaptureEngine
import com.cancapture.data.CaptureRepository
import com.cancapture.data.SettingsRepository
import com.cancapture.data.settingsDataStore

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val settings = SettingsRepository(settingsDataStore)
        val captures = CaptureRepository(this)
        container = AppContainer(
            settingsRepository = settings,
            captureRepository = captures,
            captureEngine = CaptureEngine(settings, captures),
        )
    }
}

data class AppContainer(
    val settingsRepository: SettingsRepository,
    val captureRepository: CaptureRepository,
    val captureEngine: CaptureEngine,
)
