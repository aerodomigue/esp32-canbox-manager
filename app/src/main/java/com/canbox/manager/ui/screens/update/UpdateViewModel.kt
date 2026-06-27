package com.canbox.manager.ui.screens.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.github.GitHubRepository
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.OtaCrcMismatchException
import com.canbox.manager.data.usb.OtaProtocol
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.FirmwareInfo
import com.canbox.manager.domain.model.GitHubRelease
import com.canbox.manager.domain.model.UpdateProgress
import com.canbox.manager.domain.model.UpdateState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class UpdateUiState(
    val isLoading: Boolean = false,
    val isLoadingReleases: Boolean = false,
    val currentFirmware: FirmwareInfo = FirmwareInfo(),
    val releases: List<GitHubRelease> = emptyList(),
    val updateProgress: UpdateProgress = UpdateProgress(UpdateState.IDLE),
    val error: String? = null
)

class UpdateViewModel(
    private val repository: CanBoxRepository,
    private val gitHubRepository: GitHubRepository
) : ViewModel() {

    companion object {
        private const val TAG = "UpdateViewModel"
        private const val MAX_FLASH_RETRIES = 3
    }

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

    fun loadReleases() {
        viewModelScope.launch {
            android.util.Log.d("UpdateViewModel", "loadReleases called")
            _uiState.update { it.copy(isLoadingReleases = true, error = null) }

            gitHubRepository.getReleases()
                .onSuccess { releases ->
                    android.util.Log.d("UpdateViewModel", "Got ${releases.size} releases, updating state")
                    _uiState.update {
                        it.copy(isLoadingReleases = false, releases = releases)
                    }
                    android.util.Log.d("UpdateViewModel", "State updated, releases count: ${_uiState.value.releases.size}")
                }
                .onFailure { error ->
                    android.util.Log.e("UpdateViewModel", "Failed to load releases", error)
                    _uiState.update {
                        it.copy(isLoadingReleases = false, error = "Failed to load releases: ${error.message}")
                    }
                }
        }
    }

    fun downloadAndInstall(release: GitHubRelease, cacheDir: File) {
        val firmwareAsset = release.firmwareAsset ?: run {
            _uiState.update { it.copy(error = "No firmware file in release") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    updateProgress = UpdateProgress(
                        state = UpdateState.DOWNLOADING,
                        totalBytes = firmwareAsset.size,
                        message = "Downloading ${release.tagName}..."
                    )
                )
            }

            val extension = if (firmwareAsset.name.endsWith(".ota")) "ota" else "bin"
            val targetFile = File(cacheDir, "firmware_${release.tagName}.$extension")

            gitHubRepository.downloadFirmware(
                url = firmwareAsset.downloadUrl,
                targetFile = targetFile,
                onProgress = { progress ->
                    _uiState.update {
                        it.copy(
                            updateProgress = it.updateProgress.copy(
                                progress = progress,
                                bytesTransferred = (firmwareAsset.size * progress).toLong()
                            )
                        )
                    }
                }
            ).onSuccess { file ->
                _uiState.update {
                    it.copy(
                        updateProgress = UpdateProgress(
                            state = UpdateState.PREPARING,
                            progress = 1f,
                            message = "Download complete. Ready to flash."
                        )
                    )
                }
                // TODO: Flash firmware via OTA or esptool
                flashFirmware(file)
            }.onFailure { error ->
                android.util.Log.e(TAG, "downloadAndInstall: download failed", error)
                _uiState.update {
                    it.copy(
                        updateProgress = UpdateProgress(
                            state = UpdateState.ERROR,
                            message = "Download failed: ${error.message}"
                        )
                        // Don't set error field - the updateProgress.ERROR state shows the message
                    )
                }
            }
        }
    }

    /**
     * Flash firmware using OTA protocol v2 (base64 + CRC32 per chunk)
     *
     * Pre-flight : OTA ABORT → 1s drain (built into otaAbort timeout)
     * Start      : OTA START <size> <md5>  →  info lines … OK READY
     * Data       : OTA DATA <base64> <crc32>  →  OK <recv>/<total> (<pct>%)
     *              CRC mismatch → retry same chunk (OtaProtocol.MAX_CRC_RETRIES times)
     * End        : OTA END  →  MD5 verified OK … OK
     * Retry full : up to MAX_FLASH_RETRIES on non-CRC errors
     */
    private suspend fun flashFirmware(firmwareFile: File) {
        val firmwareData = firmwareFile.readBytes()
        val md5 = firmwareData.md5()
        val firmwareSize = firmwareData.size
        android.util.Log.d(TAG, "OTA: size=$firmwareSize, md5=$md5")

        var lastError: Exception? = null

        for (attempt in 1..MAX_FLASH_RETRIES) {
            try {
                android.util.Log.d(TAG, "OTA: Attempt $attempt/$MAX_FLASH_RETRIES")

                _uiState.update {
                    it.copy(
                        updateProgress = UpdateProgress(
                            state = UpdateState.PREPARING,
                            message = if (attempt > 1) "Retry $attempt/$MAX_FLASH_RETRIES..." else "Preparing OTA update..."
                        )
                    )
                }

                // Pre-flight: clear any in-progress OTA; 1s drain baked into otaAbort timeout
                repository.otaAbort()

                _uiState.update {
                    it.copy(
                        updateProgress = UpdateProgress(
                            state = UpdateState.FLASHING,
                            message = if (attempt > 1) "Starting OTA (attempt $attempt)..." else "Starting OTA update...",
                            totalBytes = firmwareSize.toLong()
                        )
                    )
                }

                // Step 1: Start OTA
                val startResponse = repository.otaStart(firmwareSize, md5)
                    .getOrElse { error ->
                        android.util.Log.e(TAG, "OTA: Start failed - ${error.message}")
                        throw error
                    }
                android.util.Log.d(TAG, "OTA: Started - $startResponse")

                // Step 2: Send data chunks with CRC32, chunk-level retry on CRC mismatch
                var sent = 0
                var lastLogPercent = -10

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    while (sent < firmwareSize) {
                        val end = minOf(sent + OtaProtocol.CHUNK_SIZE, firmwareSize)
                        val chunk = firmwareData.sliceArray(sent until end)

                        for (crcAttempt in 1..OtaProtocol.MAX_CRC_RETRIES) {
                            val dataResult = repository.otaSendData(chunk)
                            when {
                                dataResult.isSuccess -> {
                                    val percent = (end * 100) / firmwareSize
                                    if (percent >= lastLogPercent + 10) {
                                        lastLogPercent = percent
                                        android.util.Log.d(TAG, "OTA: $percent% ($end/$firmwareSize) - ${dataResult.getOrNull()}")
                                    }
                                    break  // chunk accepted — exit retry loop
                                }
                                dataResult.exceptionOrNull() is OtaCrcMismatchException -> {
                                    android.util.Log.w(TAG, "OTA: CRC mismatch at $sent, retry $crcAttempt/${OtaProtocol.MAX_CRC_RETRIES}")
                                    if (crcAttempt == OtaProtocol.MAX_CRC_RETRIES) throw dataResult.exceptionOrNull()!!
                                    kotlinx.coroutines.delay(OtaProtocol.CRC_RETRY_PAUSE_MS)
                                }
                                else -> {
                                    android.util.Log.e(TAG, "OTA: Data failed at $sent: ${dataResult.exceptionOrNull()?.message}")
                                    throw dataResult.exceptionOrNull()!!
                                }
                            }
                        }

                        // Reached only on successful chunk (loop threw on all errors)
                        sent = end
                        val percent = (sent * 100) / firmwareSize
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            _uiState.update {
                                it.copy(
                                    updateProgress = it.updateProgress.copy(
                                        state = UpdateState.FLASHING,
                                        progress = sent.toFloat() / firmwareSize,
                                        bytesTransferred = sent.toLong(),
                                        totalBytes = firmwareSize.toLong(),
                                        message = if (attempt > 1) "Flashing (attempt $attempt)... $percent%" else "Flashing... $percent%"
                                    )
                                )
                            }
                        }
                    }
                }

                android.util.Log.d(TAG, "OTA: Transfer complete, finalizing...")
                _uiState.update {
                    it.copy(
                        updateProgress = it.updateProgress.copy(
                            message = "Verifying MD5..."
                        )
                    )
                }

                // Step 3: Finalize
                val endResponse = repository.otaEnd()
                    .getOrElse { error ->
                        android.util.Log.e(TAG, "OTA: End failed - ${error.message}")
                        throw error
                    }
                android.util.Log.d(TAG, "OTA: Complete! $endResponse")

                _uiState.update {
                    it.copy(
                        updateProgress = UpdateProgress(
                            state = UpdateState.SUCCESS,
                            progress = 1f,
                            message = "Firmware updated! Rebooting..."
                        )
                    )
                }

                // Wait for device to reboot and reconnect
                kotlinx.coroutines.delay(5000)
                loadCurrentFirmware()

                // Success - exit retry loop
                return

            } catch (e: Exception) {
                android.util.Log.e(TAG, "OTA: Attempt $attempt failed - ${e.message}")
                lastError = e

                // Try to abort before retry
                try {
                    repository.otaAbort()
                } catch (_: Exception) {}

                if (attempt < MAX_FLASH_RETRIES) {
                    _uiState.update {
                        it.copy(
                            updateProgress = UpdateProgress(
                                state = UpdateState.PREPARING,
                                message = "Attempt $attempt failed, retrying..."
                            )
                        )
                    }
                    kotlinx.coroutines.delay(2000) // Wait before retry
                }
            }
        }

        // All retries failed
        android.util.Log.e(TAG, "OTA: All $MAX_FLASH_RETRIES attempts failed")
        _uiState.update {
            it.copy(
                updateProgress = UpdateProgress(
                    state = UpdateState.ERROR,
                    message = "Flash failed after $MAX_FLASH_RETRIES attempts: ${lastError?.message}"
                )
            )
        }
    }

    fun installFromFile(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch {
            try {
                val displayName = context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment

                if (displayName?.endsWith(".ota", ignoreCase = true) != true) {
                    _uiState.update {
                        it.copy(
                            updateProgress = UpdateProgress(
                                state = UpdateState.ERROR,
                                message = "Invalid file: only .ota firmware files are supported"
                            )
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        updateProgress = UpdateProgress(
                            state = UpdateState.PREPARING,
                            message = "Reading firmware file..."
                        )
                    )
                }

                // Copy URI content to a temp file
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")

                val tempFile = File(context.cacheDir, "firmware_local.bin")
                tempFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()

                android.util.Log.d(TAG, "Loaded local firmware: ${tempFile.length()} bytes")
                flashFirmware(tempFile)

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load firmware file", e)
                _uiState.update {
                    it.copy(
                        updateProgress = UpdateProgress(
                            state = UpdateState.ERROR,
                            message = "Failed to load file: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun resetUpdateState() {
        _uiState.update { it.copy(updateProgress = UpdateProgress(UpdateState.IDLE)) }
    }

    private fun ByteArray.md5(): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(this)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
