package com.canbox.manager.ui.screens.debug

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.CanFilter
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
    val filters: List<CanFilter> = defaultFilters(),
    val totalFrames: Long = 0,
    val framesPerSecond: Int = 0,
    val error: String? = null
) {
    companion object {
        fun defaultFilters() = listOf(
            CanFilter(0x002, "Steering", true),
            CanFilter(0x180, "RPM", true),
            CanFilter(0x284, "Speed", true),
            CanFilter(0x5C5, "Fuel", true),
            CanFilter(0x60D, "Doors", true),
            CanFilter(0x6F6, "Voltage", true)
        )
    }
}

class DebugViewModel(
    private val repository: CanBoxRepository
) : ViewModel() {

    companion object {
        private const val MAX_FRAMES = 500
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

    fun toggleFilter(canId: Int) {
        _uiState.update { state ->
            state.copy(
                filters = state.filters.map { filter ->
                    if (filter.canId == canId) {
                        filter.copy(enabled = !filter.enabled)
                    } else {
                        filter
                    }
                }
            )
        }
    }

    fun addFilter(canId: Int, label: String) {
        _uiState.update { state ->
            if (state.filters.any { it.canId == canId }) {
                state
            } else {
                state.copy(filters = state.filters + CanFilter(canId, label, true))
            }
        }
    }

    private fun collectFrames() {
        frameCollectionJob = viewModelScope.launch {
            repository.canFrames.collect { frame ->
                if (!_uiState.value.isPaused) {
                    val enabledFilters = _uiState.value.filters.filter { it.enabled }.map { it.canId }

                    // If no filters are enabled, show all. Otherwise, filter.
                    val shouldShow = enabledFilters.isEmpty() || frame.canId in enabledFilters

                    if (shouldShow) {
                        _uiState.update { state ->
                            val newFrames = (listOf(frame) + state.frames).take(MAX_FRAMES)
                            state.copy(
                                frames = newFrames,
                                totalFrames = state.totalFrames + 1
                            )
                        }
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun exportToFile(context: Context) {
        viewModelScope.launch {
            val frames = _uiState.value.frames
            if (frames.isEmpty()) {
                _uiState.update { it.copy(error = "No frames to export") }
                return@launch
            }

            try {
                val file = withContext(Dispatchers.IO) {
                    createExportFile(context, frames)
                }

                // Share the file
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(Intent.createChooser(shareIntent, "Export CAN Log"))
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Export failed: ${e.message}") }
            }
        }
    }

    private fun createExportFile(context: Context, frames: List<CanFrame>): File {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val filename = "canlog_${dateFormat.format(Date())}.csv"

        val exportDir = File(context.cacheDir, "exports")
        exportDir.mkdirs()
        val file = File(exportDir, filename)

        file.bufferedWriter().use { writer ->
            // CSV header
            writer.write("Timestamp,Direction,CAN_ID,DLC,Data\n")

            // Frames (reversed to have oldest first)
            frames.reversed().forEach { frame ->
                val time = timeFormat.format(Date(frame.timestamp))
                val canId = "0x%03X".format(frame.canId)
                val data = frame.data.joinToString(" ") { "%02X".format(it) }
                writer.write("$time,${frame.direction},$canId,${frame.dlc},$data\n")
            }
        }

        return file
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            repository.stopCanLogging()
        }
    }
}
