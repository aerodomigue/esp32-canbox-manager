package com.canbox.manager.ui.screens.debug

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.log.CanLogEntry
import com.canbox.manager.data.log.ExternalLogExporter
import com.canbox.manager.data.log.ExternalStoragePermissionRequired
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File

data class DebugUiState(
    val isLogging: Boolean = false,
    val isPaused: Boolean = false,
    val logEntries: List<CanLogEntry> = emptyList(),
    val totalFrames: Long = 0,
    val error: String? = null,
    val savedPath: String? = null,
    val recoverablePaths: List<String> = emptyList(),
    val isExporting: Boolean = false,
    val storagePermissionRequired: Boolean = false,
    val exportMessage: String? = null
)

class DebugViewModel(
    private val repository: CanBoxRepository,
    private val externalExporter: ExternalLogExporter,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        internal const val DISPLAY_FRAMES = 200
    }

    val connectionState: StateFlow<UsbConnectionState> = repository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UsbConnectionState.Disconnected
        )

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()
    private var pendingSnapshot: File?
        get() = savedStateHandle.get<String>("pendingExternalExport")?.let(::File)
        set(value) { savedStateHandle["pendingExternalExport"] = value?.absolutePath }

    init {
        // Logging state and file persistence belong to the application-scoped
        // repository, so destroying this screen cannot stop a capture session.
        viewModelScope.launch {
            repository.canLoggingState.collect { logState ->
                _uiState.update { state ->
                    state.copy(
                        isLogging = logState.isLogging,
                        totalFrames = logState.totalFrames,
                        error = logState.error ?: state.error,
                        savedPath = savedStateHandle.get<String>("lastExportSnapshot")
                            ?: logState.lastSavedFile?.absolutePath
                            ?: logState.recoverableFiles.firstOrNull()?.absolutePath
                            ?: state.savedPath,
                        recoverablePaths = logState.recoverableFiles.map { it.absolutePath }
                    )
                }
            }
        }

        viewModelScope.launch {
            repository.logEntries.collect { entry ->
                _uiState.update { state ->
                    if (state.isPaused) {
                        state
                    } else {
                        state.copy(
                            logEntries = (listOf(entry) + state.logEntries)
                                .take(DISPLAY_FRAMES)
                        )
                    }
                }
            }
        }
    }

    fun startLogging() {
        // A new capture must resume the live display after a previous pause.
        _uiState.update { it.copy(isPaused = false) }
        viewModelScope.launch {
            repository.startCanLogging()
                .onFailure { error -> _uiState.update { it.copy(error = "Start failed: ${error.message}") } }
        }
    }

    fun stopLogging() {
        viewModelScope.launch {
            repository.stopCanLogging()
                .onFailure { error -> _uiState.update { it.copy(error = "Stop failed: ${error.message}") } }
        }
    }

    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    fun clearFrames() {
        _uiState.update {
            it.copy(
                logEntries = emptyList()
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, savedPath = null, exportMessage = null) }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    fun saveToFile() {
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true, error = null, exportMessage = null) }
        viewModelScope.launch {
            try {
                val snapshot = repository.saveCanLog().getOrThrow()
                savedStateHandle["lastExportSnapshot"] = snapshot.absolutePath
                _uiState.update { it.copy(savedPath = snapshot.absolutePath) }
                exportSnapshot(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setError("Export impossible : ${error.message}")
            } finally {
                _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    private suspend fun exportSnapshot(snapshot: File) {
        try {
            val destination = withContext(Dispatchers.IO) {
                val jobContext = coroutineContext
                externalExporter.export(snapshot) { jobContext.ensureActive() }
            }
            _uiState.update {
                it.copy(exportMessage = if (destination != null) {
                    "Log complet copié sur stockage externe : ${destination.absolutePath}"
                } else {
                    "Aucune clé USB / SD montée. Log complet sauvegardé localement."
                })
            }
        } catch (_: ExternalStoragePermissionRequired) {
            pendingSnapshot = snapshot
            _uiState.update { it.copy(storagePermissionRequired = true) }
        }
    }

    fun storagePermissionOpened() {
        _uiState.update { it.copy(storagePermissionRequired = false) }
    }

    fun onStoragePermissionResult(granted: Boolean) {
        val snapshot = pendingSnapshot ?: return
        pendingSnapshot = null
        if (!granted) {
            setError("Accès aux fichiers non autorisé. Copie locale conservée ; export USB / SD impossible.")
            return
        }
        if (!snapshot.isFile) {
            setError("Le fichier local à exporter n’est plus disponible. Relancer la sauvegarde.")
            return
        }
        savedStateHandle["lastExportSnapshot"] = snapshot.absolutePath
        _uiState.update { it.copy(savedPath = snapshot.absolutePath) }
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            try {
                exportSnapshot(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setError("Export externe impossible : ${error.message}. Copie locale conservée.")
            } finally {
                _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    fun sendSavedLogByEmail(context: Context) {
        val path = _uiState.value.savedPath
        if (path == null) {
            setError("Save the log before sending it")
            return
        }

        try {
            val file = File(path)
            if (!file.isFile) throw IllegalStateException("Saved log file is unavailable")

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "CANBox log - ${file.name}")
                putExtra(Intent.EXTRA_TEXT, "CANBox log attached: ${file.name}")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("CANBox log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Send CAN log"))
        } catch (_: ActivityNotFoundException) {
            setError("No email application is available")
        } catch (error: Exception) {
            setError("Email export failed: ${error.message}")
        }
    }
}
