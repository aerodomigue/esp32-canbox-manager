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
            _uiState.update { it.copy(isLoading = true, error = null) }

            repository.getCalibration()
                .onSuccess { config ->
                    originalConfig = config
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            config = config,
                            hasChanges = false
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

    fun applyChanges() {
        viewModelScope.launch {
            val config = _uiState.value.config
            _uiState.update { it.copy(isSaving = true, error = null) }

            // Apply each changed parameter
            val results = mutableListOf<Result<Unit>>()

            if (config.steeringOffset != originalConfig.steeringOffset) {
                results.add(repository.setCalibration("steer_offset", config.steeringOffset))
            }
            if (config.steeringScale != originalConfig.steeringScale) {
                results.add(repository.setCalibration("steer_scale", config.steeringScale))
            }
            if (config.steeringInvert != originalConfig.steeringInvert) {
                results.add(repository.setCalibration("steer_invert", if (config.steeringInvert) 1 else 0))
            }
            if (config.indicatorTimeout != originalConfig.indicatorTimeout) {
                results.add(repository.setCalibration("indicator_timeout", config.indicatorTimeout))
            }
            if (config.rpmDivisor != originalConfig.rpmDivisor) {
                results.add(repository.setCalibration("rpm_divisor", config.rpmDivisor))
            }
            if (config.tankCapacity != originalConfig.tankCapacity) {
                results.add(repository.setCalibration("tank_capacity", config.tankCapacity))
            }
            if (config.dteDivisor != originalConfig.dteDivisor) {
                results.add(repository.setCalibration("dte_divisor", config.dteDivisor))
            }

            val failed = results.any { it.isFailure }
            if (failed) {
                _uiState.update {
                    it.copy(isSaving = false, error = "Failed to apply some settings")
                }
            } else {
                _uiState.update {
                    it.copy(isSaving = false, successMessage = "Settings applied (not saved to NVS)")
                }
            }
        }
    }

    fun saveToNvs() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }

            // First apply all changes
            applyChanges()

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
                    loadConfig()
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
