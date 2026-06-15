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
import com.cancapture.data.ChannelConfig
import com.cancapture.data.IsoTp
import com.cancapture.data.SettingsRepository
import com.cancapture.data.SocketcandClient
import com.cancapture.data.UdsClient
import com.cancapture.data.UdsPoller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant

enum class BusPhase { Connecting, Listening, Active, Disconnected, Errored }
enum class BusMode { Passive, UdsPoll }

data class BusStatus(
    val name: String,
    val phase: BusPhase,
    val mode: BusMode = BusMode.Passive,
    val frameCount: Int = 0,
    val lastFrameId: String? = null,
    val errorMessage: String? = null,
    val polls: Int = 0,
    val pollErrors: Int = 0,
    val lastPollError: String? = null,
)

sealed interface RecordUiState {
    data object Idle : RecordUiState
    data class Recording(
        val startedAtMs: Long,
        val elapsedMs: Long,
        val buses: List<BusStatus>,
    ) : RecordUiState {
        val frameCount: Int get() = buses.sumOf { it.frameCount }
    }
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
        if (_state.value is RecordUiState.Recording) return
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            val configs = settings.channels.filter { it.bus.isNotBlank() }
            if (configs.isEmpty()) {
                _state.value = RecordUiState.Error("No buses configured")
                return@launch
            }

            val temp = captureRepo.newTempFile()
            tempFile = temp
            val createdAt = Instant.now()
            val ascWriter = AscWriter(
                BufferedWriter(OutputStreamWriter(temp.outputStream(), Charsets.US_ASCII)),
                createdAt
            )
            writer = ascWriter
            withContext(Dispatchers.IO) { ascWriter.writeHeader() }

            val startedAtMs = System.currentTimeMillis()
            _state.value = RecordUiState.Recording(
                startedAtMs = startedAtMs,
                elapsedMs = 0,
                buses = configs.map {
                    BusStatus(
                        name = it.bus,
                        phase = BusPhase.Connecting,
                        mode = if (it is ChannelConfig.UdsPoll) BusMode.UdsPoll else BusMode.Passive,
                    )
                },
            )

            val writerMutex = Mutex()
            val originMutex = Mutex()
            var firstFrameTs: Double? = null

            val origin: suspend (Double) -> Double = { ts ->
                originMutex.withLock {
                    firstFrameTs ?: ts.also { firstFrameTs = it }
                }
            }

            try {
                supervisorScope {
                    configs.forEachIndexed { index, config ->
                        val ch = index + 1
                        // Run each channel off the main thread: viewModelScope is
                        // Dispatchers.Main.immediate, and letting a busy bus's
                        // per-frame work run there starves the other channel's
                        // ISO-TP reassembly, overrunning multi-frame poll timeouts.
                        launch(Dispatchers.Default) {
                            runChannel(
                                config = config,
                                index = index,
                                channelNum = ch,
                                host = settings.host,
                                port = settings.port,
                                ascWriter = ascWriter,
                                writerMutex = writerMutex,
                                origin = origin,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }

            // All channels ended on their own.
            runCatching { withContext(Dispatchers.IO) { ascWriter.close() } }
            writer = null
            val cur = _state.value as? RecordUiState.Recording ?: return@launch
            if (cur.frameCount > 0) {
                _state.value = RecordUiState.PendingSave(
                    tempFile = temp,
                    durationMs = cur.elapsedMs,
                    frameCount = cur.frameCount,
                    createdAt = Instant.ofEpochMilli(cur.startedAtMs)
                )
            } else {
                captureRepo.discardTemp(temp)
                tempFile = null
                val msg = cur.buses.firstNotNullOfOrNull { it.errorMessage }
                    ?: "Disconnected before any frames were received"
                _state.value = RecordUiState.Error(msg)
            }
        }
    }

    private suspend fun runChannel(
        config: ChannelConfig,
        index: Int,
        channelNum: Int,
        host: String,
        port: Int,
        ascWriter: AscWriter,
        writerMutex: Mutex,
        origin: suspend (Double) -> Double,
    ) {
        val client = SocketcandClient(host, port, config.bus, channelNum)
        try {
            coroutineScope {
                val session = client.connect(this)
                try {
                    updateBus(index) { cur ->
                        if (cur.phase == BusPhase.Connecting) cur.copy(phase = BusPhase.Listening)
                        else cur
                    }

                    val isoTp: IsoTp? = if (config is ChannelConfig.UdsPoll) {
                        IsoTp(
                            session = session,
                            txId = config.txId,
                            rxId = config.rxId,
                            extended = config.extended,
                            blockSize = config.blockSize,
                            stMinMs = config.stMinMs,
                            paddingByte = config.paddingByte,
                            timeoutMs = config.timeoutMs.toLong(),
                        )
                    } else null

                    val pollerJob: Job? = if (isoTp != null && config is ChannelConfig.UdsPoll) {
                        val poller = UdsPoller(
                            uds = UdsClient(isoTp),
                            entries = config.entries,
                            periodMs = config.periodMs,
                        )
                        launch {
                            poller.run { stats ->
                                updateBus(index) { cur ->
                                    cur.copy(
                                        polls = stats.polls,
                                        pollErrors = stats.errors,
                                        lastPollError = stats.lastError,
                                    )
                                }
                            }
                        }
                    } else null

                    try {
                        session.frames.collect { frame ->
                            // Feed ISO-TP reassembly first (a non-blocking offer)
                            // so the response — and the flow-control frame we owe
                            // the ECU — never waits behind logging I/O.
                            isoTp?.onFrame(frame)
                            withContext(Dispatchers.IO) {
                                writerMutex.withLock { ascWriter.writeFrame(frame) }
                            }
                            val t0 = origin(frame.timestamp)
                            val relMs = ((frame.timestamp - t0) * 1000.0).toLong().coerceAtLeast(0L)
                            val idStr = if (frame.extended) "%X".format(frame.id) + "x"
                            else "%X".format(frame.id)
                            updateBus(index) { cur ->
                                cur.copy(
                                    phase = BusPhase.Active,
                                    frameCount = cur.frameCount + 1,
                                    lastFrameId = idStr,
                                )
                            }
                            advanceElapsed(relMs)
                        }
                    } finally {
                        pollerJob?.cancel()
                    }
                    updateBus(index) { cur ->
                        if (cur.phase == BusPhase.Errored) cur
                        else cur.copy(phase = BusPhase.Disconnected)
                    }
                } finally {
                    session.close()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateBus(index) { cur ->
                cur.copy(
                    phase = BusPhase.Errored,
                    errorMessage = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    private fun updateBus(index: Int, mutator: (BusStatus) -> BusStatus) {
        _state.update { cur ->
            if (cur !is RecordUiState.Recording) return@update cur
            if (index !in cur.buses.indices) return@update cur
            val updated = cur.buses.toMutableList()
            updated[index] = mutator(updated[index])
            cur.copy(buses = updated)
        }
    }

    private fun advanceElapsed(relMs: Long) {
        _state.update { cur ->
            if (cur !is RecordUiState.Recording) return@update cur
            if (relMs <= cur.elapsedMs) cur else cur.copy(elapsedMs = relMs)
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
