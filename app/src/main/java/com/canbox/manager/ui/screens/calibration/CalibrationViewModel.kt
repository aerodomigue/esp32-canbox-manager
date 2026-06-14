package com.canbox.manager.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.CalibrationConfig
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CalibrationUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val config: CalibrationConfig = CalibrationConfig(),
    val hasChanges: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

class CalibrationViewModel(
    private val repository: CanBoxRepository
) : ViewModel() {

    val connectionState: StateFlow<UsbConnectionState> = repository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UsbConnectionState.Disconnected
        )

    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()

    private var originalConfig = CalibrationConfig()

    fun loadConfig() {
        viewModelScope.launch {
            loadConfigInternal()
        }
    }

    private suspend fun loadConfigInternal() {
        _uiState.update { it.copy(isLoading = true, error = null) }

        repository.getCalibration()
            .onSuccess { config ->
                originalConfig = config
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isSaving = false,
                        config = config,
                        hasChanges = false
                    )
                }
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, isSaving = false, error = error.message)
                }
            }
    }

    fun updateSteeringOffset(value: Int) {
        updateConfig { it.copy(steeringOffset = value) }
    }

    fun updateSteeringScale(value: Int) {
        updateConfig { it.copy(steeringScale = value) }
    }

    fun updateSteeringInvert(value: Boolean) {
        updateConfig { it.copy(steeringInvert = value) }
    }

    fun updateIndicatorTimeout(value: Int) {
        updateConfig { it.copy(indicatorTimeout = value) }
    }

    fun updateRpmDivisor(value: Int) {
        updateConfig { it.copy(rpmDivisor = value) }
    }

    fun updateTankCapacity(value: Int) {
        updateConfig { it.copy(tankCapacity = value) }
    }

    fun updateDteDivisor(value: Int) {
        updateConfig { it.copy(dteDivisor = value) }
    }

    private fun updateConfig(update: (CalibrationConfig) -> CalibrationConfig) {
        _uiState.update { state ->
            val newConfig = update(state.config)
            state.copy(
                config = newConfig,
                hasChanges = newConfig != originalConfig
            )
        }
    }

    private fun applyChanges() {
        viewModelScope.launch {
            applyChangesInternal()
        }
    }

    private suspend fun applyChangesInternal(): Boolean {
        val config = _uiState.value.config
        _uiState.update { it.copy(isSaving = true, error = null) }

        // Apply each changed parameter with delay between commands
        var success = true

        if (config.steeringOffset != originalConfig.steeringOffset) {
            if (repository.setCalibration("steerOffset", config.steeringOffset).isFailure) success = false
            kotlinx.coroutines.delay(100)
        }
        if (config.steeringScale != originalConfig.steeringScale) {
            if (repository.setCalibration("steerScale", config.steeringScale).isFailure) success = false
            kotlinx.coroutines.delay(100)
        }
        if (config.steeringInvert != originalConfig.steeringInvert) {
            if (repository.setCalibration("steerInvert", if (config.steeringInvert) 1 else 0).isFailure) success = false
            kotlinx.coroutines.delay(100)
        }
        if (config.indicatorTimeout != originalConfig.indicatorTimeout) {
            if (repository.setCalibration("indTimeout", config.indicatorTimeout).isFailure) success = false
            kotlinx.coroutines.delay(100)
        }
        if (config.rpmDivisor != originalConfig.rpmDivisor) {
            if (repository.setCalibration("rpmDiv", config.rpmDivisor).isFailure) success = false
            kotlinx.coroutines.delay(100)
        }
        if (config.tankCapacity != originalConfig.tankCapacity) {
            if (repository.setCalibration("tankCap", config.tankCapacity).isFailure) success = false
            kotlinx.coroutines.delay(100)
        }
        if (config.dteDivisor != originalConfig.dteDivisor) {
            if (repository.setCalibration("dteDiv", config.dteDivisor).isFailure) success = false
            kotlinx.coroutines.delay(100)
        }

        if (!success) {
            _uiState.update {
                it.copy(isSaving = false, error = "Failed to apply some settings")
            }
        } else {
            _uiState.update {
                it.copy(isSaving = false, successMessage = "Settings applied (not saved to NVS)")
            }
        }

        return success
    }

    fun saveToNvs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }

            // First apply all changes and wait
            val applySuccess = applyChangesInternal()
            if (!applySuccess) return@launch

            // Delay before save
            kotlinx.coroutines.delay(200)

            // Then save to NVS
            repository.saveCalibration()
                .onSuccess {
                    originalConfig = _uiState.value.config
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            hasChanges = false,
                            successMessage = "Settings saved to NVS"
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isSaving = false, error = error.message)
                    }
                }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }

            repository.resetCalibration()
                .onSuccess {
                    // Wait before reloading config
                    kotlinx.coroutines.delay(200)
                    loadConfigInternal()
                    _uiState.update {
                        it.copy(successMessage = "Reset to defaults")
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isSaving = false, error = error.message)
                    }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
