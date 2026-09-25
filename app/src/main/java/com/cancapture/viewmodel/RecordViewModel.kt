package com.cancapture.viewmodel

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cancapture.App
import com.cancapture.CaptureService
import com.cancapture.data.CaptureEngine
import com.cancapture.data.RecordUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin UI-facing shell over the process-wide [CaptureEngine]. Starting goes
 * through [CaptureService] so the capture is promoted to a foreground service
 * before any sockets open.
 */
class RecordViewModel(
    application: Application,
    private val engine: CaptureEngine,
) : AndroidViewModel(application) {

    val state: StateFlow<RecordUiState> = engine.state

    fun start() {
        if (state.value is RecordUiState.Recording) return
        val app = getApplication<Application>()
        ContextCompat.startForegroundService(app, Intent(app, CaptureService::class.java))
    }

    fun stop() = engine.stop()
    fun saveAs(name: String) = engine.saveAs(name)
    fun discardPending() = engine.discardPending()
    fun dismissError() = engine.dismissError()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App
                RecordViewModel(app, app.container.captureEngine)
            }
        }
    }
}
