package com.canbox.manager.data.repository

import android.content.Context
import com.canbox.manager.data.log.CanLogEntry
import com.canbox.manager.data.log.CanLogStorage
import com.canbox.manager.data.usb.CommandParser
import com.canbox.manager.data.usb.SerialLineBuffer
import com.canbox.manager.data.usb.OtaCrcMismatchException
import com.canbox.manager.data.usb.OtaProtocol
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.data.usb.UsbSerialManager
import com.canbox.manager.data.usb.crc32Hex
import com.canbox.manager.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class CanBoxRepository(
    private val usbManager: UsbSerialManager,
    appContext: Context
) {
    companion object {
        private const val TAG = "CanBoxRepository"
        private const val LOGGING_REQUESTED_KEY = "logging_requested"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logStorage = CanLogStorage.fromDirectory(
        appContext.getExternalFilesDir(null)?.let {
            CanLogStorage.externalDirectory(it)
        } ?: File(appContext.filesDir, "canbox")
    )
    private val loggingMutex = Mutex()
    private val loggingPreferences = appContext.getSharedPreferences(
        "canbox_logging",
        Context.MODE_PRIVATE
    )

    // Connection state
    val connectionState: StateFlow<UsbConnectionState> = usbManager.connectionState

    // Vehicle data polling
    private val _vehicleData = MutableStateFlow(VehicleData())
    val vehicleData: StateFlow<VehicleData> = _vehicleData.asStateFlow()

    private var pollingJob: Job? = null
    private var logStreamJob: Job? = null

    private val _logEntries = MutableSharedFlow<CanLogEntry>(replay = 0, extraBufferCapacity = 1000)
    val logEntries: SharedFlow<CanLogEntry> = _logEntries.asSharedFlow()

    @Volatile
    private var isLogging = false
    private var loggingSessionId: Long? = null
    private val logLineBuffer = SerialLineBuffer()

    private val _canLoggingState = MutableStateFlow(
        CanLoggingState(recoverableFiles = logStorage.recoveredFiles())
    )
    val canLoggingState: StateFlow<CanLoggingState> = _canLoggingState.asStateFlow()

    init {
        // Keep the parser and durable writer owned by the application-scoped
        // repository, not by a screen ViewModel. This allows logging to
        // continue while the activity is backgrounded or recreated.
        startLogStream()
        scope.launch {
            connectionState
                .collect {
                    // A unique session ID also catches rapid reconnects when StateFlow
                    // conflates the intermediate disconnected/connecting states.
                    loggingMutex.withLock {
                        val session = (connectionState.value as? UsbConnectionState.Connected)?.sessionId
                        if (isLogging && (session == null || session != loggingSessionId)) {
                            stopCanLoggingSync()
                        }
                    }
                    if (connectionState.value.isConnected && isCanLoggingRequested() && !isLogging) {
                        resumeCanLoggingIfRequested()
                    }
                }
        }
    }

    fun connect(): Boolean {
        return usbManager.connect()
    }

    fun disconnect() {
        stopPolling()
        stopCanLoggingSync()
        usbManager.disconnect()
    }

    /** Resume a session requested before a process/activity restart. */
    fun isCanLoggingRequested(): Boolean =
        loggingPreferences.getBoolean(LOGGING_REQUESTED_KEY, false)

    suspend fun resumeCanLoggingIfRequested(): Result<Unit> {
        if (!isCanLoggingRequested()) {
            return Result.success(Unit)
        }
        return startCanLogging()
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
        // Start upload — response is "OK READY" (not plain "OK"), check with startsWith
        val startResult = usbManager.sendCommand("CAN UPLOAD START $filename ${content.size}")
        val startResponse = startResult.getOrDefault("")
        if (startResult.isFailure || !startResponse.lines().any { it.trim().startsWith("OK") }) {
            return Result.failure(Exception("Upload start failed: $startResponse"))
        }

        // Send data in chunks (150 bytes = 200 chars base64, conservative for reliability)
        val chunkSize = 150
        var totalSent = 0
        for (i in content.indices step chunkSize) {
            val chunk = content.sliceArray(i until minOf(i + chunkSize, content.size))
            val base64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
            val dataResult = usbManager.sendCommand("CAN UPLOAD DATA $base64", timeoutMs = 5000)
            val dataResponse = dataResult.getOrDefault("")
            if (dataResult.isFailure || !dataResponse.lines().any { it.trim().startsWith("OK") }) {
                usbManager.sendCommand("CAN UPLOAD ABORT")
                return Result.failure(Exception("Upload data failed at offset $i: $dataResponse"))
            }
            totalSent += chunk.size
            // Small delay to let ESP32 process
            kotlinx.coroutines.delay(10)
        }

        // End upload
        return usbManager.sendCommand("CAN UPLOAD END", timeoutMs = 5000).map {
            if (!CommandParser.isSuccess(it)) {
                throw Exception(CommandParser.getErrorMessage(it) ?: "Upload end failed")
            }
        }
    }

    // Debug logging commands
    suspend fun startCanLogging(): Result<Unit> = loggingMutex.withLock {
        if (isLogging) return@withLock Result.success(Unit)

        try {
            logStorage.open()
        } catch (error: Exception) {
            return@withLock Result.failure(error)
        }

        // Enable capture before sending LOG ON so frames received in the same
        // USB chunk as the command response are not silently discarded.
        loggingSessionId = (connectionState.value as? UsbConnectionState.Connected)?.sessionId
        isLogging = true
        _canLoggingState.value = CanLoggingState(
            isLogging = true,
            recoverableFiles = logStorage.recoveredFiles()
        )

        val result = usbManager.sendCommand("LOG ON")
        if (result.isSuccess) {
            withContext(Dispatchers.IO) {
                loggingPreferences.edit().putBoolean(LOGGING_REQUESTED_KEY, true).commit()
            }
        } else {
            stopCanLoggingSync()
        }
        result.map { }
    }

    suspend fun stopCanLogging(): Result<Unit> = loggingMutex.withLock {
        val result = if (isLogging) {
            usbManager.sendCommand("LOG OFF")
        } else {
            Result.success("")
        }
        withContext(Dispatchers.IO) {
            loggingPreferences.edit().putBoolean(LOGGING_REQUESTED_KEY, false).commit()
        }
        stopCanLoggingSync(clean = true)
        result.map { }
    }

    private fun stopCanLoggingSync(clean: Boolean = false) {
        isLogging = false
        loggingSessionId = null
        logLineBuffer.clear()
        logStorage.close(clean = clean)
        _canLoggingState.update {
            it.copy(
                isLogging = false,
                recoverableFiles = logStorage.recoveredFiles()
            )
        }
    }

    private fun startLogStream() {
        if (logStreamJob?.isActive == true) return
        logStreamJob = scope.launch {
            usbManager.receivedData.collect { data ->
                // USB reads are arbitrary chunks. Keep incomplete lines until
                // the next read so a frame split across reads is not lost.
                val completeLines = logLineBuffer.append(data)

                if (!isLogging) return@collect
                completeLines.forEach { rawLine ->
                    val entry = CanLogEntry.fromRawLine(rawLine) ?: return@forEach

                    try {
                        // RX and ODB lines are exported byte-for-byte as received.
                        // Display and export both use the original line.
                        logStorage.writeRawFrame(rawLine)
                        _canLoggingState.update {
                            it.copy(totalFrames = it.totalFrames + 1)
                        }
                        _logEntries.tryEmit(entry)
                    } catch (error: Exception) {
                        android.util.Log.e(TAG, "CAN log write failed", error)
                        isLogging = false
                        logStorage.close(clean = false)
                        _canLoggingState.update {
                            it.copy(
                                isLogging = false,
                                error = "Log storage failed: ${error.message}"
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun saveCanLog(): Result<File> = withContext(Dispatchers.IO) {
        runCatching { logStorage.save() }
            .onSuccess { file ->
                _canLoggingState.update {
                    it.copy(
                        lastSavedFile = file,
                        recoverableFiles = logStorage.recoveredFiles()
                    )
                }
            }
    }

    // Firmware update commands
    suspend fun enterBootloader(): Result<Unit> {
        return usbManager.sendCommand("SYS BOOTLOADER", timeoutMs = 5000).map {
            // Device will disconnect after this
        }
    }

    // ========== OTA Protocol (Base64, text responses) ==========

    /**
     * Start OTA update session
     * Response: "OK READY" or "ERROR: <message>"
     */
    suspend fun otaStart(size: Int, md5: String): Result<String> {
        val cmd = "OTA START $size $md5"
        android.util.Log.d(TAG, "OTA START - sending: $cmd")
        return usbManager.sendCommand(cmd, timeoutMs = OtaProtocol.START_TIMEOUT_MS).fold(
            onSuccess = { response ->
                android.util.Log.d(TAG, "OTA START - response: $response")
                if (response.contains("OK READY")) {
                    Result.success(response)
                } else {
                    Result.failure(Exception(parseError(response)))
                }
            },
            onFailure = { error ->
                android.util.Log.e(TAG, "OTA START - failed: ${error.message}")
                Result.failure(error)
            }
        )
    }

    /**
     * Send firmware data chunk (base64 encoded + CRC32)
     * Chunk size: 180 bytes binary = 240 chars base64
     * Response: "OK <received>/<total> (<percent>%)" or "ERROR: CRC mismatch chunk …" (retry safe)
     */
    suspend fun otaSendData(data: ByteArray): Result<String> {
        val base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        val crc = data.crc32Hex()
        return usbManager.sendCommand("OTA DATA $base64 $crc", timeoutMs = OtaProtocol.CHUNK_TIMEOUT_MS).fold(
            onSuccess = { response ->
                when {
                    response.contains("OK") -> Result.success(response)
                    response.contains("CRC mismatch", ignoreCase = true) ->
                        Result.failure(OtaCrcMismatchException(response))
                    else -> Result.failure(Exception(parseError(response)))
                }
            },
            onFailure = { error ->
                android.util.Log.e(TAG, "OTA DATA - failed: ${error.message}")
                Result.failure(error)
            }
        )
    }

    /**
     * Finalize OTA update
     * Response: "MD5 verified OK\nOK" or "ERROR: <message>"
     * ESP32 reboots automatically after 2 seconds
     */
    suspend fun otaEnd(): Result<String> {
        android.util.Log.d(TAG, "OTA END - sending")
        return usbManager.sendCommand("OTA END", timeoutMs = OtaProtocol.END_TIMEOUT_MS).fold(
            onSuccess = { response ->
                android.util.Log.d(TAG, "OTA END - response: $response")
                // Terminal line is exactly "OK" on its own line (not "MD5 verified OK")
                if (response.lines().any { it.trim() == "OK" }) {
                    Result.success(response)
                } else {
                    Result.failure(Exception(parseError(response)))
                }
            },
            onFailure = { error ->
                android.util.Log.e(TAG, "OTA END - failed: ${error.message}")
                Result.failure(error)
            }
        )
    }

    /**
     * Abort OTA in progress (pre-flight or cancel).
     * Response "OTA aborted" has no OK/ERROR prefix — sendCommand will timeout after 1s.
     * This 1s timeout acts as the required RX drain before the next OTA START.
     */
    suspend fun otaAbort(): Result<Unit> {
        return usbManager.sendCommand("OTA ABORT", timeoutMs = 1000).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.success(Unit) }  // Ignore timeout / errors
        )
    }

    /**
     * Get OTA status (for debugging)
     */
    suspend fun otaStatus(): Result<String> {
        return usbManager.sendCommand("OTA STATUS", timeoutMs = 2000)
    }

    private fun parseError(response: String): String {
        // Extract error message from "ERROR: <message>" format
        val errorPrefix = "ERROR:"
        val idx = response.indexOf(errorPrefix)
        return if (idx >= 0) {
            response.substring(idx + errorPrefix.length).trim()
        } else {
            response
        }
    }

    fun release() {
        scope.cancel()
        logStorage.close()
        usbManager.release()
    }

}

data class CanLoggingState(
    val isLogging: Boolean = false,
    val totalFrames: Long = 0,
    val lastSavedFile: File? = null,
    val recoverableFiles: List<File> = emptyList(),
    val error: String? = null
)
