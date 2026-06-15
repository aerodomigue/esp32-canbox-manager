package com.canbox.manager.ui.screens.debug

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.CanFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

data class DebugUiState(
    val isLogging: Boolean = false,
    val isPaused: Boolean = false,
    val frames: List<CanFrame> = emptyList(),
    val totalFrames: Long = 0,
    val error: String? = null,
    val savedPath: String? = null
)

class DebugViewModel(
    private val repository: CanBoxRepository,
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val DISPLAY_FRAMES = 200
        private const val LOG_DIR = "canbox"
        private const val TEMP_FILE_NAME = "canlog_tmp.txt"
        private const val FLUSH_EVERY_N_FRAMES = 100
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    val connectionState: StateFlow<UsbConnectionState> = repository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UsbConnectionState.Disconnected
        )

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    private var frameCollectionJob: kotlinx.coroutines.Job? = null
    private var tempLogFile: File? = null

    // Guards all access to tempLogWriter — prevents close-while-writing race
    private val writerMutex = Mutex()
    private var tempLogWriter: BufferedWriter? = null

    // Separate counter for file writes, unaffected by clearFrames()
    private var framesWrittenToFile = 0L

    fun startLogging() {
        if (_uiState.value.isLogging) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dir = getSaveDirectory()
                dir.mkdirs()
                val tempFile = File(dir, TEMP_FILE_NAME)
                tempLogFile = tempFile
                framesWrittenToFile = 0L
                writerMutex.withLock {
                    tempLogWriter = BufferedWriter(FileWriter(tempFile, false))
                    tempLogWriter?.write("# CANBox log\n")
                    tempLogWriter?.write("# Time              CAN_ID  DLC  Data\n")
                    tempLogWriter?.flush()
                }
            }

            repository.startCanLogging()
                .onSuccess {
                    _uiState.update { it.copy(isLogging = true, isPaused = false) }
                    collectFrames()
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message) }
                    withContext(Dispatchers.IO) { closeWriterLocked() }
                }
        }
    }

    fun stopLogging() {
        viewModelScope.launch {
            frameCollectionJob?.cancel()
            frameCollectionJob?.join()
            repository.stopCanLogging()
            withContext(Dispatchers.IO) { closeWriterLocked() }
            _uiState.update { it.copy(isLogging = false) }
        }
    }

    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    fun clearFrames() {
        _uiState.update { it.copy(frames = emptyList()) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, savedPath = null) }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    private fun collectFrames() {
        frameCollectionJob = viewModelScope.launch {
            try {
                repository.canFrames.collect { frame ->
                    withContext(Dispatchers.IO) {
                        writerMutex.withLock {
                            tempLogWriter?.let { writer ->
                                val time = timeFormat.format(Date(frame.timestamp))
                                val id = "0x%03X".format(frame.canId)
                                val data = frame.data.joinToString(" ") { "%02X".format(it) }
                                writer.write("$time  $id  [${frame.dlc}]  $data\n")
                                framesWrittenToFile++
                                if (framesWrittenToFile % FLUSH_EVERY_N_FRAMES == 0L) {
                                    writer.flush()
                                }
                            }
                        }
                    }

                    _uiState.update { state ->
                        val newFrames = if (!state.isPaused) {
                            (listOf(frame) + state.frames).take(DISPLAY_FRAMES)
                        } else {
                            state.frames
                        }
                        state.copy(frames = newFrames, totalFrames = state.totalFrames + 1)
                    }
                }
            } finally {
                // NonCancellable: withContext in a cancelled coroutine throws CancellationException
                // without it — this flush would never execute on job cancellation
                withContext(NonCancellable + Dispatchers.IO) {
                    writerMutex.withLock { tempLogWriter?.flush() }
                }
            }
        }
    }

    fun saveToFile() {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    // Flush buffered data before copying — no need to stop collection,
                    // the OS guarantees reads see all flushed bytes; one or two frames
                    // written during the copy is acceptable for a log file
                    writerMutex.withLock { tempLogWriter?.flush() }

                    val tempFile = tempLogFile
                        ?: throw Exception("No log — start logging first")
                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        throw Exception("No frames to save")
                    }
                    val finalFile = File(getSaveDirectory(), "canlog_${dateFormat.format(Date())}.txt")
                    tempFile.copyTo(finalFile, overwrite = true)
                    finalFile
                }
                _uiState.update { it.copy(savedPath = file.absolutePath) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Save failed: ${e.message}") }
            }
        }
    }

    private fun getSaveDirectory(): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File(appContext.getExternalFilesDir(null), LOG_DIR)
        } else {
            File(Environment.getExternalStorageDirectory(), LOG_DIR)
        }
    }

    // Must be called inside withContext(Dispatchers.IO)
    private suspend fun closeWriterLocked() {
        writerMutex.withLock {
            try { tempLogWriter?.flush() } catch (_: Exception) {}
            try { tempLogWriter?.close() } catch (_: Exception) {}
            tempLogWriter = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled here — use runBlocking to honour the mutex
        // (the finally block in collectFrames may still hold it briefly)
        frameCollectionJob?.cancel()
        runBlocking {
            writerMutex.withLock {
                try { tempLogWriter?.flush() } catch (_: Exception) {}
                try { tempLogWriter?.close() } catch (_: Exception) {}
                tempLogWriter = null
            }
        }
    }
}
