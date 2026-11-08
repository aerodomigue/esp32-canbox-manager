package com.canbox.manager.domain.repository

import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.CalibrationConfig
import kotlinx.coroutines.flow.StateFlow

interface CalibrationRepository {
    val connectionState: StateFlow<UsbConnectionState>
    suspend fun <T> withCalibrationOperation(action: suspend () -> T): T
    suspend fun getCalibration(sessionId: Long): Result<CalibrationConfig>
    suspend fun setCalibration(param: String, value: Int, sessionId: Long): Result<Unit>
    suspend fun saveCalibration(sessionId: Long): Result<Unit>
    suspend fun resetCalibration(sessionId: Long): Result<Unit>
    suspend fun reloadVehicleCalibration(sessionId: Long): Result<Unit>
}
