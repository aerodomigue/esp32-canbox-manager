package com.canbox.manager.data.usb

import com.canbox.manager.data.log.CanLogEntry

/** Command replies and unsolicited logs share the wire, but not response state. */
internal class CommandResponseBuffer {
    private val pendingLine = StringBuilder()
    private var ignorePendingLine = false
    private val response = StringBuilder()
    private var command: String? = null
    private var dashboardStarted = false
    private var responseLines = 0

    fun begin(command: String) {
        // Keep an incomplete serial line: it may belong to an ODB notification
        // which began before this command was sent.
        this.command = command
        // A reply or prompt which started before begin cannot acknowledge it.
        ignorePendingLine = pendingLine.isNotEmpty()
        response.clear()
        dashboardStarted = false
        responseLines = 0
    }

    fun end() { command = null }

    fun reset() {
        end()
        pendingLine.clear()
        ignorePendingLine = false
        response.clear()
    }

    fun partialResponse(): String = response.toString()

    fun append(chunk: String): String? {
        var completed: String? = null
        for (char in chunk) {
            pendingLine.append(char)
            // Recognize the prompt at the beginning of a line even when the
            // next log immediately follows it in the same USB read.
            val isPrompt = pendingLine.length == 2 && pendingLine[0] == '>' && char == ' '
            if (char == '\n' || isPrompt) {
                val rawLine = pendingLine.toString()
                pendingLine.clear()
                if (!ignorePendingLine) {
                    acceptLine(rawLine, isPrompt)?.let { completed = it }
                }
                ignorePendingLine = false
            }
        }
        return completed
    }

    private fun acceptLine(rawLine: String, isPrompt: Boolean): String? {
        val activeCommand = command ?: return null
        if (CanLogEntry.isLogLine(rawLine)) return null
        val line = rawLine.trim()
        val isError = line.startsWith("ERROR")
        val isDashboard = activeCommand == "SYS DATA"
        if (isDashboard && isPrompt) return null
        if (isDashboard && !dashboardStarted && !isError) {
            if (line != "=== Live Vehicle Data ===") return null
            dashboardStarted = true
        }
        response.append(rawLine)
        responseLines++
        val done = if (isDashboard) {
            isError || (dashboardStarted && line == "=========================")
        } else {
            // OTA aborted intentionally is not a terminal response.
            isError || line.startsWith("OK") || line == ">" ||
                (responseLines >= 2 && line.length >= 10 && line.all { it == '=' })
        }
        return if (done) {
            end()
            response.toString().trim()
        } else null
    }
}
