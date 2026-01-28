package com.canbox.manager.data.repository

import com.canbox.manager.data.usb.CommandParser
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.data.usb.UsbSerialManager
import com.canbox.manager.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CanBoxRepository(
    private val usbManager: UsbSerialManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Connection state
    val connectionState: StateFlow<UsbConnectionState> = usbManager.connectionState

    // Vehicle data polling
    private val _vehicleData = MutableStateFlow(VehicleData())
    val vehicleData: StateFlow<VehicleData> = _vehicleData.asStateFlow()

    private var pollingJob: Job? = null
    private var logStreamJob: Job? = null

    // CAN frame streaming for debug
    private val _canFrames = MutableSharedFlow<CanFrame>(replay = 0, extraBufferCapacity = 1000)
    val canFrames: SharedFlow<CanFrame> = _canFrames.asSharedFlow()

    private var isLogging = false

    fun connect(): Boolean {
        return usbManager.connect()
    }

    fun disconnect() {
        stopPolling()
        stopCanLoggingSync()
        usbManager.disconnect()
    }

    // System commands
    suspend fun getSysInfo(): Result<FirmwareInfo> {
        return usbManager.sendCommand("SYS INFO").map { response ->
            CommandParser.parseSysInfo(response)
        }
    }

    suspend fun getSysData(): Result<VehicleData> {
        return usbManager.sendCommand("SYS DATA").map { response ->
            CommandParser.parseSysData(response)
        }
    }

    // Polling for live data
    fun startPolling(intervalMs: Long = 300L) {
        if (pollingJob?.isActive == true) return

        pollingJob = scope.launch {
            while (isActive) {
                if (connectionState.value.isConnected) {
                    getSysData().onSuccess { data ->
                        _vehicleData.value = data
                    }
                }
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // Calibration commands
    suspend fun getCalibration(): Result<CalibrationConfig> {
        return usbManager.sendCommand("CFG LIST").map { response ->
            CommandParser.parseCfgList(response)
        }
    }

    suspend fun setCalibration(param: String, value: Int): Result<Unit> {
        return usbManager.sendCommand("CFG SET $param $value").fold(
            onSuccess = { response ->
                if (CommandParser.isSuccess(response)) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(CommandParser.getErrorMessage(response) ?: "Set failed"))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun saveCalibration(): Result<Unit> {
        return usbManager.sendCommand("CFG SAVE").fold(
            onSuccess = { response ->
                if (CommandParser.isSuccess(response)) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(CommandParser.getErrorMessage(response) ?: "Save failed"))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun resetCalibration(): Result<Unit> {
        return usbManager.sendCommand("CFG RESET").fold(
            onSuccess = { response ->
                if (CommandParser.isSuccess(response)) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(CommandParser.getErrorMessage(response) ?: "Reset failed"))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    // CAN config commands
    suspend fun getCanStatus(): Result<CanConfigStatus> {
        // Get status first
        val statusResult = usbManager.sendCommand("CAN STATUS")
        if (statusResult.isFailure) {
            return Result.failure(statusResult.exceptionOrNull() ?: Exception("CAN STATUS failed"))
        }

        val (activeConfig, mode) = CommandParser.parseCanStatus(statusResult.getOrThrow())

        // Delay to let ESP32 finish processing
        delay(200)

        // Then get file list
        val listResult = usbManager.sendCommand("CAN LIST")
        val files = if (listResult.isSuccess) {
            CommandParser.parseCanList(listResult.getOrThrow())
        } else {
            emptyList()
        }

        return Result.success(
            CanConfigStatus(
                activeConfig = activeConfig,
                mode = mode,
                files = files.map { file ->
                    file.copy(isActive = file.filename == activeConfig)
                }
            )
        )
    }

    suspend fun loadCanConfig(filename: String): Result<Unit> {
        return usbManager.sendCommand("CAN LOAD $filename").map {
            if (!CommandParser.isSuccess(it)) {
                throw Exception(CommandParser.getErrorMessage(it) ?: "Load failed")
            }
        }
    }

    suspend fun deleteCanConfig(filename: String): Result<Unit> {
        return usbManager.sendCommand("CAN DELETE $filename").map {
            if (!CommandParser.isSuccess(it)) {
                throw Exception(CommandParser.getErrorMessage(it) ?: "Delete failed")
            }
        }
    }

    suspend fun uploadCanConfig(filename: String, content: ByteArray): Result<Unit> {
        // Start upload
        val startResult = usbManager.sendCommand("CAN UPLOAD START $filename ${content.size}")
        if (startResult.isFailure || !CommandParser.isSuccess(startResult.getOrDefault(""))) {
            return Result.failure(Exception("Upload start failed"))
        }

        // Send data in chunks
        val chunkSize = 512
        for (i in content.indices step chunkSize) {
            val chunk = content.sliceArray(i until minOf(i + chunkSize, content.size))
            val base64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
            val dataResult = usbManager.sendCommand("CAN UPLOAD DATA $base64")
            if (dataResult.isFailure) {
                return Result.failure(Exception("Upload data failed at offset $i"))
            }
        }

        // End upload
        return usbManager.sendCommand("CAN UPLOAD END").map {
            if (!CommandParser.isSuccess(it)) {
                throw Exception(CommandParser.getErrorMessage(it) ?: "Upload end failed")
            }
        }
    }

    // Debug logging commands
    suspend fun startCanLogging(): Result<Unit> {
        val result = usbManager.sendCommand("LOG ON")
        if (result.isSuccess) {
            isLogging = true
            startLogStream()
        }
        return result.map { }
    }

    suspend fun stopCanLogging(): Result<Unit> {
        stopCanLoggingSync()
        return usbManager.sendCommand("LOG OFF").map { }
    }

    private fun stopCanLoggingSync() {
        isLogging = false
        logStreamJob?.cancel()
        logStreamJob = null
    }

    private fun startLogStream() {
        logStreamJob = scope.launch {
            usbManager.receivedData.collect { data ->
                if (isLogging) {
                    // Parse each line as a potential CAN frame
                    data.lines().forEach { line ->
                        CommandParser.parseCanFrame(line)?.let { frame ->
                            _canFrames.emit(frame)
                        }
                    }
                }
            }
        }
    }

    // Firmware update commands
    suspend fun enterBootloader(): Result<Unit> {
        return usbManager.sendCommand("SYS BOOTLOADER", timeoutMs = 5000).map {
            // Device will disconnect after this
        }
    }

    suspend fun startOtaUpdate(size: Int, md5: String): Result<Unit> {
        return usbManager.sendCommand("OTA START $size $md5").map {
            if (!CommandParser.isSuccess(it)) {
                throw Exception(CommandParser.getErrorMessage(it) ?: "OTA start failed")
            }
        }
    }

    suspend fun sendOtaData(data: ByteArray): Result<Unit> {
        val base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        return usbManager.sendCommand("OTA DATA $base64", timeoutMs = 5000).map {
            if (!CommandParser.isSuccess(it)) {
                throw Exception(CommandParser.getErrorMessage(it) ?: "OTA data failed")
            }
        }
    }

    suspend fun endOtaUpdate(): Result<Unit> {
        return usbManager.sendCommand("OTA END", timeoutMs = 30000).map {
            if (!CommandParser.isSuccess(it)) {
                throw Exception(CommandParser.getErrorMessage(it) ?: "OTA end failed")
            }
        }
    }

    fun release() {
        scope.cancel()
        usbManager.release()
    }
}
