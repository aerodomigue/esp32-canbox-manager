package com.canbox.manager.data.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Durable storage for a CAN logging session.
 *
 * The temporary file is intentionally kept while a session is running and
 * after it is saved. This means a background/service restart can still leave
 * a recoverable file on disk instead of losing the captured frames.
 */
class CanLogStorage(private val directory: File) {

    companion object {
        private const val TEMP_FILE_NAME = "canlog_tmp.txt"
        private const val FLUSH_EVERY_N_FRAMES = 100L
        private const val RECOVERED_PREFIX = "canlog_recovered_"

        fun fromDirectory(directory: File): CanLogStorage = CanLogStorage(directory)

        fun externalDirectory(externalFilesDirectory: File): File =
            File(externalFilesDirectory, "Documents/canbox")
    }

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())

    private var writer: BufferedWriter? = null
    private var outputStream: FileOutputStream? = null
    private var frameCount = 0L
    private var lastFlushAtMs = 0L

    val tempFile: File
        get() = File(directory, TEMP_FILE_NAME)

    @Synchronized
    fun open() {
        close(clean = false)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Cannot create log directory: ${directory.absolutePath}")
        }

        // Preserve a partial session left by a previous process before starting
        // a new one. The fixed temp filename is then safe to overwrite.
        if (tempFile.exists() && containsFrameData(tempFile)) {
            val recovered = uniqueFile("$RECOVERED_PREFIX${dateFormat.format(Date())}")
            tempFile.copyTo(recovered, overwrite = false)
        }

        writer = BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(tempFile, false).also { outputStream = it },
                StandardCharsets.UTF_8
            )
        ).also {
            it.flush()
        }
        frameCount = 0L
        lastFlushAtMs = System.currentTimeMillis()
    }

    @Synchronized
    /**
     * Writes the frame exactly as received from the device.
     *
     * The caller must provide the original line, including its line ending.
     * No timestamp, CAN ID formatting, DLC or payload normalization is added.
     */
    fun writeRawFrame(rawLine: String) {
        val currentWriter = writer ?: return
        currentWriter.write(rawLine)
        frameCount++
        val now = System.currentTimeMillis()
        if (frameCount % FLUSH_EVERY_N_FRAMES == 0L || now - lastFlushAtMs >= 500L) {
            currentWriter.flush()
            outputStream?.fd?.sync()
            lastFlushAtMs = now
        }
    }

    @Synchronized
    fun flush() {
        writer?.flush()
        outputStream?.fd?.sync()
        lastFlushAtMs = System.currentTimeMillis()
    }

    @Synchronized
    fun save(): File {
        flush()
        val tempHasFrames = tempFile.exists() && containsFrameData(tempFile)
        val recoveredSource = if (!tempHasFrames) recoveredFiles().firstOrNull() else null
        val source = if (tempHasFrames) tempFile else recoveredSource
        if (source == null) {
            throw IllegalStateException("No frames to save")
        }

        val finalFile = uniqueFile("canlog_${dateFormat.format(Date())}")
        val stagingFile = File(directory, ".${finalFile.name}.part")
        source.copyTo(stagingFile, overwrite = true)
        syncFile(stagingFile)
        if (!stagingFile.renameTo(finalFile)) {
            stagingFile.copyTo(finalFile, overwrite = false)
            syncFile(finalFile)
            stagingFile.delete()
        }
        if (source != tempFile) source.delete()
        return finalFile
    }

    @Synchronized
    fun close(clean: Boolean = false) {
        try {
            writer?.flush()
            outputStream?.fd?.sync()
        } catch (_: Exception) {
        }
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        outputStream = null
        writer = null
    }

    @Synchronized
    fun hasFrames(): Boolean = frameCount > 0L || (tempFile.exists() && containsFrameData(tempFile))

    @Synchronized
    fun recoveredFiles(): List<File> {
        val recovered = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(RECOVERED_PREFIX) && it.extension == "txt" }
            .sortedBy { it.name }
        return if (tempFile.exists() && containsFrameData(tempFile)) {
            listOf(tempFile) + recovered
        } else {
            recovered
        }
    }

    @Synchronized
    fun currentFrameCount(): Long = frameCount

    private fun containsFrameData(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return file.useLines(StandardCharsets.UTF_8) { lines ->
            lines.any { it.isNotBlank() && !it.startsWith("#") }
        }
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun uniqueFile(baseName: String): File {
        var candidate = File(directory, "$baseName.txt")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName-$suffix.txt")
            suffix++
        }
        return candidate
    }

}
