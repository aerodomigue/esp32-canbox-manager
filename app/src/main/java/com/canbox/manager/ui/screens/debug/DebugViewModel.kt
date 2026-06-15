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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private val appContext: Context,
    private val dirProvider: () -> File = { defaultLogDir(appContext) }
) : ViewModel() {

    companion object {
        internal const val DISPLAY_FRAMES = 200
        private const val LOG_DIR = "canbox"

        internal fun defaultLogDir(context: Context): File =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                File(context.getExternalFilesDir(null), LOG_DIR)
            } else {
                File(Environment.getExternalStorageDirectory(), LOG_DIR)
            }
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
    private var logFileManager: LogFileManager? = null

    fun startLogging() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                logFileManager = LogFileManager(dirProvider()).also { it.open() }
            }

            repository.startCanLogging()
                .onSuccess {
                    _uiState.update { it.copy(isLogging = true, isPaused = false) }
                    collectFrames()
                }
                .onFailure { error ->
                    _uiState.update { it.copy(error = error.message) }
                    withContext(Dispatchers.IO) { logFileManager?.close() }
                }
        }
    }

    fun stopLogging() {
        viewModelScope.launch {
            frameCollectionJob?.cancel()
            repository.stopCanLogging()
            withContext(Dispatchers.IO) { logFileManager?.close() }
            _uiState.update { it.copy(isLogging = false) }
        }
    }

    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    fun clearFrames() {
        _uiState.update { it.copy(frames = emptyList(), totalFrames = 0) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, savedPath = null) }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    private fun collectFrames() {
        frameCollectionJob = viewModelScope.launch {
            repository.canFrames.collect { frame ->
                withContext(Dispatchers.IO) { logFileManager?.writeFrame(frame) }

                _uiState.update { state ->
                    val newFrames = if (!state.isPaused) {
                        (listOf(frame) + state.frames).take(DISPLAY_FRAMES)
                    } else {
                        state.frames
                    }
                    state.copy(frames = newFrames, totalFrames = state.totalFrames + 1)
                }
            }
        }
    }

    fun saveToFile() {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    logFileManager?.save() ?: throw IllegalStateException("No log — start logging first")
                }
                _uiState.update { it.copy(savedPath = file.absolutePath) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Save failed: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            repository.stopCanLogging()
            withContext(Dispatchers.IO) { logFileManager?.close() }
        }
    }
}
