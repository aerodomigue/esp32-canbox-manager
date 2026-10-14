package com.canbox.manager.data.log

data class CanLogEntry(val rawLine: String) {
    companion object {
        fun isLogLine(line: String): Boolean =
            line.startsWith("RX") || line.startsWith("ODB ")

        // Only the prefix matters. Never decode or rebuild a captured log line.
        fun fromRawLine(rawLine: String): CanLogEntry? =
            if (isLogLine(rawLine)) CanLogEntry(rawLine) else null
    }
}
