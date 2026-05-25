package com.cancapture.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cancapture.App
import com.cancapture.data.AscWriter
import com.cancapture.data.CaptureRepository
import com.cancapture.data.SettingsRepository
import com.cancapture.data.SocketcandClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant

sealed interface RecordUiState {
    data object Idle : RecordUiState
    data class Connecting(val host: String, val port: Int, val bus: String) : RecordUiState
    data class Recording(
        val startedAtMs: Long,
        val elapsedMs: Long,
        val frameCount: Int,
        val lastFrameId: String?
    ) : RecordUiState
    data class PendingSave(
        val tempFile: File,
        val durationMs: Long,
        val frameCount: Int,
        val createdAt: Instant
    ) : RecordUiState
    data class Error(val message: String) : RecordUiState
}

class RecordViewModel(
    application: Application,
    private val settingsRepo: SettingsRepository,
    private val captureRepo: CaptureRepository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<RecordUiState>(RecordUiState.Idle)
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    private var captureJob: Job? = null
    private var tempFile: File? = null
    private var writer: AscWriter? = null

    fun start() {
        if (_state.value is RecordUiState.Recording || _state.value is RecordUiState.Connecting) return
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            _state.value = RecordUiState.Connecting(settings.host, settings.port, settings.bus)

            val temp = captureRepo.newTempFile()
            tempFile = temp
            val createdAt = Instant.now()
            val ascWriter = AscWriter(
                BufferedWriter(OutputStreamWriter(temp.outputStream(), Charsets.US_ASCII)),
                createdAt
            )
            writer = ascWriter
            withContext(Dispatchers.IO) { ascWriter.writeHeader() }

            val client = SocketcandClient(settings.host, settings.port, settings.bus)
            val startNanos = System.nanoTime()

            _state.value = RecordUiState.Recording(
                startedAtMs = System.currentTimeMillis(),
                elapsedMs = 0,
                frameCount = 0,
                lastFrameId = null
            )

            val timerJob = launch {
                while (isActive) {
                    delay(100)
                    val cur = _state.value
                    if (cur is RecordUiState.Recording) {
                        val elapsed = (System.nanoTime() - startNanos) / 1_000_000L
                        _state.value = cur.copy(elapsedMs = elapsed)
                    } else break
                }
            }

            try {
                client.frames().collect { frame ->
                    withContext(Dispatchers.IO) { ascWriter.writeFrame(frame) }
                    val cur = _state.value
                    if (cur is RecordUiState.Recording) {
                        val idStr = (if (frame.extended) "%X".format(frame.id) + "x"
                        else "%X".format(frame.id))
                        _state.value = cur.copy(
                            frameCount = cur.frameCount + 1,
                            lastFrameId = idStr
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Orderly stop() is in progress; let it finalize state.
                timerJob.cancel()
                throw e
            } catch (e: java.io.IOException) {
                timerJob.cancel()
                runCatching { withContext(Dispatchers.IO) { ascWriter.close() } }
                captureRepo.discardTemp(temp)
                tempFile = null
                writer = null
                _state.value = RecordUiState.Error(e.message ?: "Capture failed")
                return@launch
            }
            // Flow completed naturally (server closed the connection).
            timerJob.cancel()
            runCatching { withContext(Dispatchers.IO) { ascWriter.close() } }
            val cur = _state.value as? RecordUiState.Recording
            if (cur != null) {
                _state.value = RecordUiState.PendingSave(
                    tempFile = temp,
                    durationMs = cur.elapsedMs,
                    frameCount = cur.frameCount,
                    createdAt = Instant.ofEpochMilli(cur.startedAtMs)
                )
                writer = null
            }
        }
    }

    /**
     * Stop the active capture and move to PendingSave so the user can name it.
     */
    fun stop() {
        viewModelScope.launch {
            val cur = _state.value as? RecordUiState.Recording
            captureJob?.cancelAndJoin()
            captureJob = null
            val temp = tempFile
            val w = writer
            if (temp == null || w == null || cur == null) {
                _state.value = RecordUiState.Idle
                return@launch
            }
            runCatching { withContext(Dispatchers.IO) { w.close() } }
            writer = null
            _state.value = RecordUiState.PendingSave(
                tempFile = temp,
                durationMs = cur.elapsedMs,
                frameCount = cur.frameCount,
                createdAt = Instant.ofEpochMilli(cur.startedAtMs)
            )
        }
    }

    fun saveAs(name: String) {
        val pending = _state.value as? RecordUiState.PendingSave ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                captureRepo.finalizeCapture(
                    tempFile = pending.tempFile,
                    name = name,
                    durationMs = pending.durationMs,
                    frameCount = pending.frameCount,
                    createdAt = pending.createdAt
                )
            }
            tempFile = null
            _state.value = RecordUiState.Idle
        }
    }

    fun discardPending() {
        val pending = _state.value as? RecordUiState.PendingSave ?: return
        captureRepo.discardTemp(pending.tempFile)
        tempFile = null
        _state.value = RecordUiState.Idle
    }

    fun dismissError() {
        if (_state.value is RecordUiState.Error) _state.value = RecordUiState.Idle
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App
                RecordViewModel(app, app.container.settingsRepository, app.container.captureRepository)
            }
        }
    }
}
