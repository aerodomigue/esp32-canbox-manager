package com.canbox.manager.ui.screens.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.FirmwareInfo
import com.canbox.manager.domain.model.UpdateProgress
import com.canbox.manager.domain.model.UpdateState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UpdateUiState(
    val isLoading: Boolean = false,
    val currentFirmware: FirmwareInfo = FirmwareInfo(),
    val updateProgress: UpdateProgress = UpdateProgress(UpdateState.IDLE),
    val error: String? = null
)

class UpdateViewModel(
    private val repository: CanBoxRepository
) : ViewModel() {

    val connectionState: StateFlow<UsbConnectionState> = repository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UsbConnectionState.Disconnected
        )

    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    fun loadCurrentFirmware() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            repository.getSysInfo()
                .onSuccess { info ->
                    _uiState.update {
                        it.copy(isLoading = false, currentFirmware = info)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // TODO: Implement firmware update functionality
    // - fetchGitHubReleases()
    // - downloadFirmware()
    // - flashViaEsptool()
    // - flashViaOta()
}
