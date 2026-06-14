package com.canbox.manager.ui.screens.debug

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.CanFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private val repository: CanBoxRepository
) : ViewModel() {

    companion object {
        private const val MAX_FRAMES = 500
        private const val LOG_DIR = "canbox"
    }

    val connectionState: StateFlow<UsbConnectionState> = repository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UsbConnectionState.Disconnected
        )

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    private var frameCollectionJob: kotlinx.coroutines.Job? = null

    fun startLogging() {
        viewModelScope.launch {
            repository.startCanLogging()
                .onSuccess {
                    _uiState.update { it.copy(isLogging = true, isPaused = false) }
                    collectFrames()
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message) }
                }
        }
    }

    fun stopLogging() {
        viewModelScope.launch {
            frameCollectionJob?.cancel()
            repository.stopCanLogging()
            _uiState.update { it.copy(isLogging = false) }
        }
    }

    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    fun clearFrames() {
        _uiState.update { it.copy(frames = emptyList(), totalFrames = 0) }
    }

    private fun collectFrames() {
        frameCollectionJob = viewModelScope.launch {
            repository.canFrames.collect { frame ->
                if (!_uiState.value.isPaused) {
                    _uiState.update { state ->
                        val newFrames = (listOf(frame) + state.frames).take(MAX_FRAMES)
                        state.copy(frames = newFrames, totalFrames = state.totalFrames + 1)
                    }
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, savedPath = null) }
    }

    fun saveToFile(context: Context) {
        viewModelScope.launch {
            val frames = _uiState.value.frames
            if (frames.isEmpty()) {
                _uiState.update { it.copy(error = "No frames to save") }
                return@launch
            }

            try {
                val file = withContext(Dispatchers.IO) { createLogFile(context, frames) }
                _uiState.update { it.copy(savedPath = file.absolutePath) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Save failed: ${e.message}") }
            }
        }
    }

    private fun createLogFile(context: Context, frames: List<CanFrame>): File {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val filename = "canlog_${dateFormat.format(Date())}.txt"

        val saveDir = getSaveDirectory(context)
        saveDir.mkdirs()
        val file = File(saveDir, filename)

        file.bufferedWriter().use { writer ->
            writer.write("# CANBox log - ${Date()}\n")
            writer.write("# Time              Dir  CAN_ID  DLC  Data\n")
            frames.reversed().forEach { frame ->
                val time = timeFormat.format(Date(frame.timestamp))
                val dir = frame.direction.name
                val id = "0x%03X".format(frame.canId)
                val data = frame.data.joinToString(" ") { "%02X".format(it) }
                writer.write("$time  $dir  $id  [${frame.dlc}]  $data\n")
            }
        }

        return file
    }

    private fun getSaveDirectory(context: Context): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return if (downloads != null && downloads.exists()) {
            File(downloads, LOG_DIR)
        } else {
            File(context.getExternalFilesDir(null), LOG_DIR)
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            repository.stopCanLogging()
        }
    }
}
