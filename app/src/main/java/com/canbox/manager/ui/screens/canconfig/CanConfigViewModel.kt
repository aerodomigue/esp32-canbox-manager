package com.canbox.manager.ui.screens.canconfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.CanConfigFile
import com.canbox.manager.domain.model.VehicleMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CanConfigUiState(
    val isLoading: Boolean = false,
    val activeConfig: String? = null,
    val mode: VehicleMode = VehicleMode.UNKNOWN,
    val deviceFiles: List<CanConfigFile> = emptyList(),
    val error: String? = null
)

class CanConfigViewModel(
    private val repository: CanBoxRepository
) : ViewModel() {

    val connectionState: StateFlow<UsbConnectionState> = repository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UsbConnectionState.Disconnected
        )

    private val _uiState = MutableStateFlow(CanConfigUiState())
    val uiState: StateFlow<CanConfigUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            repository.getCanStatus()
                .onSuccess { status ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            activeConfig = status.activeConfig,
                            mode = status.mode,
                            deviceFiles = status.files
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun loadConfig(filename: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            repository.loadCanConfig(filename)
                .onSuccess { refresh() }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun deleteConfig(filename: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            repository.deleteCanConfig(filename)
                .onSuccess { refresh() }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun uploadConfig(filename: String, content: ByteArray) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            repository.uploadCanConfig(filename, content)
                .onSuccess { refresh() }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun importFromFile(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }

                // Get filename from URI
                val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "config.json"

                // Read content
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")
                val content = inputStream.readBytes()
                inputStream.close()

                // Upload to device
                repository.uploadCanConfig(filename, content)
                    .onSuccess { refresh() }
                    .onFailure { error ->
                        _uiState.update {
                            it.copy(isLoading = false, error = error.message)
                        }
                    }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Import failed: ${e.message}")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
