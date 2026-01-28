package com.canbox.manager.ui.screens.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.github.GitHubRepository
import com.canbox.manager.data.repository.CanBoxRepository
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

            val targetFile = File(cacheDir, "firmware_${release.tagName}.bin")

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
     * Flash firmware using OTA protocol with base64 encoding
     *
     * Protocol flow:
     * 1. Send "OTA START <size> <md5>\n" → "OK READY"
     * 2. Send "OTA DATA <base64>\n" → "OK <received>/<total> (<percent>%)"
     * 3. Repeat step 2 for all chunks
     * 4. Send "OTA END\n" → "MD5 verified OK\nOK"
     * 5. ESP32 reboots automatically after 2 seconds
     *
     * Chunk size: 180 bytes binary = 240 chars base64 (fits in 256 byte buffer)
     */
    private suspend fun flashFirmware(firmwareFile: File) {
        _uiState.update {
            it.copy(
                updateProgress = UpdateProgress(
                    state = UpdateState.PREPARING,
                    message = "Preparing OTA update..."
                )
            )
        }

        try {
            val firmwareData = firmwareFile.readBytes()
            val md5 = firmwareData.md5()
            val firmwareSize = firmwareData.size
            android.util.Log.d(TAG, "OTA: size=$firmwareSize, md5=$md5")

            // Abort any previous OTA
            repository.otaAbort()

            _uiState.update {
                it.copy(
                    updateProgress = UpdateProgress(
                        state = UpdateState.FLASHING,
                        message = "Starting OTA update...",
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

            // Step 2: Send data chunks (180 bytes binary = 240 chars base64)
            val chunkSize = 180
            var sent = 0
            var lastLogPercent = -10

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                while (sent < firmwareSize) {
                    val end = minOf(sent + chunkSize, firmwareSize)
                    val chunk = firmwareData.sliceArray(sent until end)

                    val dataResponse = repository.otaSendData(chunk)
                        .getOrElse { error ->
                            android.util.Log.e(TAG, "OTA: Data failed at $sent: ${error.message}")
                            throw error
                        }

                    sent = end
                    val percent = (sent * 100) / firmwareSize

                    // Update UI
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                updateProgress = it.updateProgress.copy(
                                    state = UpdateState.FLASHING,
                                    progress = sent.toFloat() / firmwareSize,
                                    bytesTransferred = sent.toLong(),
                                    totalBytes = firmwareSize.toLong(),
                                    message = "Flashing... $percent%"
                                )
                            )
                        }
                    }

                    // Log progress every 10%
                    if (percent >= lastLogPercent + 10) {
                        lastLogPercent = percent
                        android.util.Log.d(TAG, "OTA: $percent% ($sent/$firmwareSize) - $dataResponse")
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

        } catch (e: Exception) {
            android.util.Log.e(TAG, "OTA: Failed - ${e.message}")

            // Try to abort
            try {
                repository.otaAbort()
            } catch (_: Exception) {}

            _uiState.update {
                it.copy(
                    updateProgress = UpdateProgress(
                        state = UpdateState.ERROR,
                        message = "Flash failed: ${e.message}"
                    )
                )
            }
        }
    }

    fun installFromFile(uri: android.net.Uri, context: android.content.Context) {
        viewModelScope.launch {
            try {
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
