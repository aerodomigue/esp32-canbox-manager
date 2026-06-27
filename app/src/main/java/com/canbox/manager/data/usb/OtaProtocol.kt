package com.canbox.manager.data.usb


/**
 * OTA Protocol v2 — plain-text serial commands
 *
 * Pre-flight : OTA ABORT → drain 1s
 * Start      : OTA START <size> <md5>  →  info lines … OK READY  (terminal)
 * Data       : OTA DATA <base64> <crc32hex>  →  OK <recv>/<total> (<pct>%)
 *              On CRC mismatch: ERROR: CRC mismatch chunk … (chunk NOT written, retry safe)
 * End        : OTA END  →  MD5 verified OK … OK  (terminal)
 * Abort      : OTA ABORT  →  OTA aborted
 *
 * Timeout auto-abort: 60 s without OTA DATA → ESP32 aborts autonomously.
 */
object OtaProtocol {
    // 177 bytes binary = 236 base64 chars. Total command: "OTA DATA " (9) + 236 + " " + 8 CRC + "\n" = 255 bytes.
    // 180 bytes (240 b64) = 259 bytes total → overflows the ESP32 USB-CDC 256-byte receive FIFO.
    const val CHUNK_SIZE = 177
    const val MAX_CRC_RETRIES = 3
    const val CRC_RETRY_PAUSE_MS = 200L
    const val ABORT_DRAIN_MS = 1000L       // drain after OTA ABORT before continuing
    const val START_TIMEOUT_MS = 10_000L
    const val CHUNK_TIMEOUT_MS = 10_000L
    const val END_TIMEOUT_MS = 30_000L
}

// Matches firmware's crc32_le(0, buf, len): init=0, no final XOR.
// NOT standard CRC32 (which uses init=0xFFFFFFFF and final XOR).
// Revert to java.util.zip.CRC32 once firmware fixes its crc32_le call.
fun ByteArray.crc32Hex(): String {
    var crc = 0L
    for (b in this) {
        crc = crc xor (b.toLong() and 0xFF)
        repeat(8) {
            crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
        }
    }
    return "%08x".format(crc)
}

class OtaCrcMismatchException(raw: String) : Exception("CRC mismatch: $raw")
