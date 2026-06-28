package com.canbox.manager.ui.screens.debug

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.CanFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

data class DebugUiState(
    val isLogging: Boolean = false,
    val isPaused: Boolean = false,
    val frames: List<CanFrame> = emptyList(),
    val totalFrames: Long = 0,
    val error: String? = null,
    val savedPath: String? = null,
    val usbSavedPath: String? = null
)

class DebugViewModel(
    private val repository: CanBoxRepository,
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val DISPLAY_FRAMES = 200
        private const val LOG_DIR = "canbox"
        private const val TEMP_FILE_NAME = "canlog_tmp.txt"
        private const val FLUSH_EVERY_N_FRAMES = 100
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    val connectionState: StateFlow<UsbConnectionState> = repository.connectionState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UsbConnectionState.Disconnected
        )

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    private var frameCollectionJob: kotlinx.coroutines.Job? = null

    // Single mutex guards tempLogFile + tempLogWriter together — no split-brain possible
    private val writerMutex = Mutex()
    private var tempLogFile: File? = null
    private var tempLogWriter: BufferedWriter? = null
    private var framesWrittenToFile = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun startLogging() {
        if (_uiState.value.isLogging) return

        viewModelScope.launch {
            // 1. Open temp file — all failures reported to UI, state stays consistent
            val openOk = withContext(Dispatchers.IO) {
                try {
                    safeCloseWriter()                               // close any stale writer
                    val file = File(appContext.cacheDir, TEMP_FILE_NAME)
                    writerMutex.withLock {
                        tempLogFile = file
                        framesWrittenToFile = 0L
                        tempLogWriter = BufferedWriter(FileWriter(file, false))
                        tempLogWriter!!.write("# CANBox log\n")
                        tempLogWriter!!.write("# Time              CAN_ID  DLC  Data\n")
                        tempLogWriter!!.flush()
                    }
                    true
                } catch (e: Exception) {
                    safeCloseWriter()
                    _uiState.update { it.copy(error = "Cannot open log file: ${e.message}") }
                    false
                }
            }
            if (!openOk) return@launch

            // 2. Start ESP32 CAN logging
            repository.startCanLogging()
                .onSuccess {
                    _uiState.update { it.copy(isLogging = true, isPaused = false) }
                    collectFrames()
                }
                .onFailure { e ->
                    withContext(Dispatchers.IO) { safeCloseAndDeleteTemp() }
                    _uiState.update { it.copy(error = "Start logging failed: ${e.message}") }
                }
        }
    }

    fun stopLogging() {
        viewModelScope.launch {
            frameCollectionJob?.cancel()
            frameCollectionJob?.join()

            repository.stopCanLogging()   // best-effort — ignore failure (ESP might be disconnected)

            withContext(Dispatchers.IO) { safeCloseAndDeleteTemp() }

            _uiState.update { it.copy(isLogging = false) }
        }
    }

    /**
     * Clears the on-screen display AND truncates the temp file.
     * A save after clear will only contain frames captured after this call.
     */
    fun clearFrames() {
        _uiState.update { it.copy(frames = emptyList(), totalFrames = 0) }

        viewModelScope.launch(Dispatchers.IO) {
            writerMutex.withLock {
                val file = tempLogFile ?: return@withLock
                try {
                    tempLogWriter?.close()
                    tempLogWriter = BufferedWriter(FileWriter(file, false))   // append=false → truncate
                    tempLogWriter!!.write("# CANBox log (cleared)\n")
                    tempLogWriter!!.write("# Time              CAN_ID  DLC  Data\n")
                    tempLogWriter!!.flush()
                    framesWrittenToFile = 0L
                } catch (e: Exception) {
                    // Writer in bad state — null it out; collectFrames skips null writer gracefully
                    try { tempLogWriter?.close() } catch (_: Exception) {}
                    tempLogWriter = null
                    _uiState.update { it.copy(error = "Clear failed: ${e.message}") }
                }
            }
        }
    }

    fun saveToFile() {
        viewModelScope.launch {
            try {
                val timestamp = dateFormat.format(Date())
                val loggingActive = _uiState.value.isLogging

                data class SaveResult(val file: File, val onUsb: Boolean)

                val result: SaveResult = withContext(Dispatchers.IO) {

                    // ── Step 1: flush ─────────────────────────────────────────
                    writerMutex.withLock {
                        try { tempLogWriter?.flush() } catch (_: Exception) {}
                    }

                    // ── Step 2: validate temp file ────────────────────────────
                    val tempFile = writerMutex.withLock { tempLogFile }
                        ?: throw Exception("No log file — start logging first")

                    if (!tempFile.exists())
                        throw Exception("Log file missing — it may have been deleted")
                    if (tempFile.length() == 0L)
                        throw Exception("No frames to save")

                    // ── Step 3: copy to destination (USB > internal) ──────────
                    val destFile = tryCopyToDestination(tempFile, timestamp)

                    // ── Step 4: delete old temp ───────────────────────────────
                    try { tempFile.delete() } catch (_: Exception) {}

                    // ── Step 5: reopen temp if logging is still active ────────
                    if (loggingActive) {
                        val newTemp = File(appContext.cacheDir, TEMP_FILE_NAME)
                        writerMutex.withLock {
                            try {
                                tempLogWriter?.close()
                                tempLogFile = newTemp
                                framesWrittenToFile = 0L
                                tempLogWriter = BufferedWriter(FileWriter(newTemp, false))
                                tempLogWriter!!.write("# CANBox log (continued after save)\n")
                                tempLogWriter!!.write("# Time              CAN_ID  DLC  Data\n")
                                tempLogWriter!!.flush()
                            } catch (e: Exception) {
                                // Reopen failed — logging to file paused, data already safe in destFile
                                try { tempLogWriter?.close() } catch (_: Exception) {}
                                tempLogWriter = null
                                tempLogFile = null
                                _uiState.update {
                                    it.copy(error = "Log file paused after save: ${e.message}")
                                }
                            }
                        }
                    } else {
                        writerMutex.withLock { tempLogFile = null }
                    }

                    SaveResult(file = destFile.first, onUsb = destFile.second)
                }

                _uiState.update {
                    it.copy(
                        savedPath    = if (!result.onUsb) result.file.absolutePath else null,
                        usbSavedPath = if (result.onUsb)  result.file.absolutePath else null
                    )
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Save failed: ${e.message}") }
            }
        }
    }

    fun togglePause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, savedPath = null, usbSavedPath = null) }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun collectFrames() {
        frameCollectionJob = viewModelScope.launch {
            try {
                repository.canFrames.collect { frame ->
                    withContext(Dispatchers.IO) {
                        writerMutex.withLock {
                            try {
                                tempLogWriter?.let { writer ->
                                    val time = timeFormat.format(Date(frame.timestamp))
                                    val id   = "0x%03X".format(frame.canId)
                                    val data = frame.data.joinToString(" ") { "%02X".format(it) }
                                    writer.write("$time  $id  [${frame.dlc}]  $data\n")
                                    framesWrittenToFile++
                                    if (framesWrittenToFile % FLUSH_EVERY_N_FRAMES == 0L) {
                                        writer.flush()
                                    }
                                }
                            } catch (_: Exception) {
                                // Write failure — don't crash the collection loop; UI still updates
                            }
                        }
                    }

                    _uiState.update { state ->
                        val newFrames = if (!state.isPaused) {
                            (listOf(frame) + state.frames).take(DISPLAY_FRAMES)
                        } else {
                            state.frames
                        }
                        state.copy(frames = newFrames, totalFrames = state.totalFrames + 1)
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    writerMutex.withLock {
                        try { tempLogWriter?.flush() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    /**
     * Tries USB drive first. Falls back to internal/external storage on any USB error.
     * Returns (destFile, onUsb).
     * Throws only if both destinations fail.
     */
    private fun tryCopyToDestination(src: File, timestamp: String): Pair<File, Boolean> {
        val usbRoot = findUsbVolume()
        if (usbRoot != null) {
            try {
                val dir  = File(usbRoot, LOG_DIR).also { it.mkdirs() }
                val dest = File(dir, "canlog_$timestamp.txt")
                src.copyTo(dest, overwrite = true)
                return dest to true
            } catch (_: Exception) {
                // USB failed (disconnected, full…) — fall through to internal
            }
        }
        val dir  = getSaveDirectory().also { it.mkdirs() }
        val dest = File(dir, "canlog_$timestamp.txt")
        src.copyTo(dest, overwrite = true)   // throws if this also fails → caught by saveToFile()
        return dest to false
    }

    private fun getSaveDirectory(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            File(appContext.getExternalFilesDir(null), LOG_DIR)
        } else {
            File(Environment.getExternalStorageDirectory(), LOG_DIR)
        }

    /**
     * Returns the root of the first mounted, writable, removable storage volume (USB drive).
     */
    private fun findUsbVolume(): File? {
        val sm = appContext.getSystemService(StorageManager::class.java) ?: return null
        for (vol in sm.storageVolumes) {
            if (!vol.isRemovable || vol.state != Environment.MEDIA_MOUNTED) continue
            val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                vol.directory
            } else {
                try { File(StorageVolume::class.java.getMethod("getPath").invoke(vol) as String) }
                catch (_: Exception) { null }
            }
            if (root != null && root.exists() && root.canWrite()) return root
        }
        return null
    }

    /** Close + null the writer. Always safe to call (swallows exceptions). */
    private suspend fun safeCloseWriter() {
        writerMutex.withLock {
            try { tempLogWriter?.flush() } catch (_: Exception) {}
            try { tempLogWriter?.close() } catch (_: Exception) {}
            tempLogWriter = null
        }
    }

    /** Close writer + delete temp file + null both. Always safe to call. */
    private suspend fun safeCloseAndDeleteTemp() {
        safeCloseWriter()
        try { tempLogFile?.delete() } catch (_: Exception) {}
        writerMutex.withLock { tempLogFile = null }
    }

    override fun onCleared() {
        super.onCleared()
        frameCollectionJob?.cancel()
        runBlocking {
            safeCloseAndDeleteTemp()
        }
    }
}
