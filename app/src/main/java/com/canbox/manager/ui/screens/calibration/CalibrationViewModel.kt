package com.canbox.manager.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.CalibrationConfig
import com.canbox.manager.domain.repository.CalibrationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CalibrationUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val config: CalibrationConfig? = null,
    val hasChanges: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
) {
    val isBusy: Boolean get() = isLoading || isSaving
}

class CalibrationViewModel(private val repository: CalibrationRepository) : ViewModel() {
    val connectionState = repository.connectionState
    private val _uiState = MutableStateFlow(CalibrationUiState())
    val uiState: StateFlow<CalibrationUiState> = _uiState.asStateFlow()
    private var originalConfig: CalibrationConfig? = null
    private var loadedSessionId: Long? = null
    private var operation: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            connectionState.map { (it as? UsbConnectionState.Connected)?.sessionId }
                .distinctUntilChanged().collect { session ->
                    generation++
                    operation?.cancel()
                    originalConfig = null
                    loadedSessionId = null
                    _uiState.value = CalibrationUiState()
                    if (session != null) loadConfig()
                }
        }
    }

    fun loadConfig() = runOperation(saving = false) { session ->
        repository.getCalibration(session).getOrThrow()
    }

    fun updateSteeringOffset(value: Int) {
        if (value in CalibrationConfig.STEERING_OFFSET_RANGE) updateConfig { copy(steeringOffset = value) }
    }
    fun updateSteeringScale(value: Int) {
        if (value in CalibrationConfig.STEERING_SCALE_RANGE) updateConfig { copy(steeringScale = value) }
    }
    fun updateSteeringInvert(value: Boolean) = updateConfig { copy(steeringInvert = value) }
    fun updateIndicatorTimeout(value: Int) {
        if (value in CalibrationConfig.INDICATOR_TIMEOUT_RANGE) updateConfig { copy(indicatorTimeout = value) }
    }
    fun updateRpmDivisor(value: Int) {
        if (value in CalibrationConfig.RPM_DIVISOR_RANGE) updateConfig { copy(rpmDivisor = value) }
    }
    fun updateTankCapacity(value: Int) {
        if (value in CalibrationConfig.TANK_CAPACITY_RANGE) updateConfig { copy(tankCapacity = value) }
    }
    fun updateDteDivisor(value: Int) {
        if (value in CalibrationConfig.DTE_DIVISOR_RANGE) updateConfig { copy(dteDivisor = value) }
    }

    private fun updateConfig(update: CalibrationConfig.() -> CalibrationConfig) {
        val state = _uiState.value
        if (state.isBusy || loadedSessionId != currentSession() || currentSession() == null) return
        val config = state.config?.update() ?: return
        _uiState.value = state.copy(config = config, hasChanges = config != originalConfig, successMessage = null)
    }

    fun saveToNvs() {
        val desired = _uiState.value.config ?: return
        val baseline = originalConfig ?: return
        if (!_uiState.value.hasChanges || loadedSessionId != currentSession()) return
        runOperation(successMessage = "Settings saved to NVS and verified. Vehicle JSON is unchanged.") { session ->
            // A profile may have been loaded from another tab since this screen was read.
            check(repository.getCalibration(session).getOrThrow() == baseline) {
                "Calibration changed on the ESP. Read it again before saving."
            }
            val changes = listOf(
                Triple("steerOffset", baseline.steeringOffset, desired.steeringOffset),
                Triple("steerScale", baseline.steeringScale, desired.steeringScale),
                Triple("steerInvert", if (baseline.steeringInvert) 1 else 0, if (desired.steeringInvert) 1 else 0),
                Triple("indTimeout", baseline.indicatorTimeout, desired.indicatorTimeout),
                Triple("rpmDiv", baseline.rpmDivisor, desired.rpmDivisor),
                Triple("tankCap", baseline.tankCapacity, desired.tankCapacity),
                Triple("dteDiv", baseline.dteDivisor, desired.dteDivisor)
            )
            for ((param, before, after) in changes) {
                if (before != after) {
                    requireSession(session)
                    repository.setCalibration(param, after, session).getOrThrow()
                }
            }
            requireSession(session)
            check(repository.getCalibration(session).getOrThrow() == desired) {
                "ESP values do not match the requested settings; NVS save cancelled."
            }
            requireSession(session)
            repository.saveCalibration(session).getOrThrow()
            requireSession(session)
            repository.getCalibration(session).getOrThrow().also {
                check(it == desired) { "Could not verify settings after NVS save." }
            }
        }
    }

    fun restoreVehicleValues() = runOperation(
        successMessage = "Vehicle profile reloaded; current ESP values verified."
    ) { session ->
        repository.reloadVehicleCalibration(session).getOrThrow()
        requireSession(session)
        repository.getCalibration(session).getOrThrow()
    }

    fun resetToFactoryDefaults() = runOperation(
        successMessage = "Factory defaults applied and verified. Reload or select your vehicle in CAN Config."
    ) { session ->
        repository.resetCalibration(session).getOrThrow()
        requireSession(session)
        repository.getCalibration(session).getOrThrow()
    }

    private fun currentSession() = (connectionState.value as? UsbConnectionState.Connected)?.sessionId

    private fun requireSession(session: Long) {
        check(currentSession() == session) { "USB session changed; read calibration again." }
    }

    private fun runOperation(
        saving: Boolean = true,
        successMessage: String? = null,
        action: suspend (Long) -> CalibrationConfig
    ) {
        val session = currentSession() ?: return
        if (_uiState.value.isBusy) return
        val request = ++generation
        _uiState.value = _uiState.value.copy(
            isLoading = !saving, isSaving = saving, error = null, successMessage = null
        )
        operation = viewModelScope.launch {
            try {
                val config = repository.withCalibrationOperation {
                    requireSession(session)
                    action(session)
                }
                requireSession(session)
                if (generation == request) {
                    originalConfig = config
                    loadedSessionId = session
                    _uiState.value = CalibrationUiState(config = config, successMessage = successMessage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == request && currentSession() == session) {
                    originalConfig = null
                    loadedSessionId = null
                    _uiState.value = CalibrationUiState(
                        error = "Calibration not verified: ${e.message ?: "communication failed"}. " +
                            "Some ESP values may have changed. Read from ESP before editing."
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
