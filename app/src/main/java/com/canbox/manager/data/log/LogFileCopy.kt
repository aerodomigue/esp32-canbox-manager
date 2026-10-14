package com.canbox.manager.data.log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Copies a completed snapshot byte for byte, without interpreting log lines. */
object LogFileCopy {
    fun copy(source: File, directory: File, checkAvailable: () -> Unit = {}): File {
        require(source.isFile) { "Local log is unavailable" }
        checkAvailable()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create USB log directory")
        }
        // Unique names also prevent a repeated export from overwriting an older log.
        val staging = File.createTempFile(".${source.nameWithoutExtension}_", ".part", directory)
        val destination = File(directory, staging.name.removePrefix(".").removeSuffix(".part") + ".txt")
        var published = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            source.inputStream().use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkAvailable()
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                        digest.update(buffer, 0, size)
                        count += size
                    }
                    output.fd.sync()
                }
            }
            checkAvailable()
            if (count != source.length() || count != staging.length()) {
                throw IOException("Incomplete USB copy")
            }
            val verification = MessageDigest.getInstance("SHA-256")
            staging.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkAvailable()
                    val size = input.read(buffer)
                    if (size < 0) break
                    verification.update(buffer, 0, size)
                }
            }
            if (!digest.digest().contentEquals(verification.digest())) {
                throw IOException("USB copy verification failed")
            }
            checkAvailable()
            if (destination.exists() || !staging.renameTo(destination)) {
                throw IOException("Cannot finalize USB log")
            }
            published = true
            checkAvailable()
            return destination
        } catch (error: Exception) {
            if (published) destination.delete()
            throw error
        } finally {
            staging.delete()
        }
    }
}
