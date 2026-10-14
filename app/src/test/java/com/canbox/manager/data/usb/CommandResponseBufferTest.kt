package com.canbox.manager.data.usb

import com.canbox.manager.data.log.CanLogEntry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandResponseBufferTest {
    @Test
    fun `dashboard and raw logs remain independent at every chunk boundary`() {
        val header = "=== Live Vehicle Data ===\r\n"
        val footer = "=========================\r\n"
        val logLines = listOf(
            "ODB TX cmd=0105 queue=OK\r\n",
            "RX 0x123 [1]: FF flags=EXT ts=123\n",
            "ODB RAW_RX 0x7E8 [3]: 41 05 82\r\n",
            "ODB RX pid=05 value=90 unit=°C\n",
            "ODB ERROR timeout\n"
        )
        val stream = logLines[0] + header + logLines[1] + "RPM: 800\r\n" +
            logLines[2] + logLines[3] + footer + logLines[4]
        val expectedReply = (header + "RPM: 800\r\n" + footer).trim()
        for (split in 0..stream.length) {
            val replies = CommandResponseBuffer().apply { begin("SYS DATA") }
            val logs = SerialLineBuffer()
            val captured = mutableListOf<String>()
            val responses = mutableListOf<String>()
            for (chunk in listOf(stream.take(split), stream.drop(split))) {
                captured += logs.append(chunk).filter(CanLogEntry::isLogLine)
                replies.append(chunk)?.let(responses::add)
            }
            assertEquals(logLines, captured, "Log split at $split")
            assertEquals(listOf(expectedReply), responses, "Reply split at $split")
        }
    }

    @Test
    fun `partial ODB line before a command cannot become an ERROR or OK reply`() {
        val replies = CommandResponseBuffer()
        assertNull(replies.append("ODB "))
        replies.begin("LOG ON")
        assertNull(replies.append("ERROR timeout\nODB TX cmd=0105 queue=OK\n"))
        assertEquals("OK", replies.append("OK\r\nODB COUNTERS requests=2\n"))
    }

    @Test
    fun `dashboard requires its header and newline terminated footer`() {
        val replies = CommandResponseBuffer().apply { begin("SYS DATA") }
        assertNull(replies.append("=========================\nOK\n"))
        assertNull(replies.append("=== Live Vehicle Data ===\nRPM: 800\n========================="))
        assertEquals(
            "=== Live Vehicle Data ===\nRPM: 800\n=========================",
            replies.append("\nODB STATE ready\n")
        )
    }

    @Test
    fun `ordinary command endings and OTA abort drain remain supported`() {
        val replies = CommandResponseBuffer()
        replies.begin("SYS INFO")
        assertEquals("=== System Info ===\nFirmware: 1.7\n=========================",
            replies.append("=== System Info ===\nFirmware: 1.7\n=========================\nODB STATE ready\n"))
        replies.begin("OTA START")
        assertEquals("OK READY", replies.append("OK READY\n"))
        replies.begin("SYS DATA")
        assertEquals("ERROR: unavailable", replies.append("ERROR: unavailable\n"))
        replies.begin("CFG LIST")
        assertNull(replies.append("value=1\n>"))
        assertEquals("value=1\n>", replies.append(" "))
        replies.begin("OTA ABORT")
        assertNull(replies.append("OTA aborted\n"))
    }

    @Test
    fun `a stale partial response cannot finish the next command`() {
        val replies = CommandResponseBuffer()
        replies.append("O")
        replies.begin("CFG SAVE")
        assertNull(replies.append("K\n"))
        assertEquals("OK", replies.append("OK\n"))

        replies.begin("LOG ON")
        assertEquals("OK", replies.append("OK\n>"))
        replies.begin("CFG SAVE")
        assertNull(replies.append(" "))
        assertEquals("OK", replies.append("OK\n"))
    }

    @Test
    fun `prompt followed by ODB in the same read is independent of fragmentation`() {
        val stream = "value=1\n> ODB STATE ready\n"
        for (split in 0..stream.length) {
            val replies = CommandResponseBuffer().apply { begin("CFG LIST") }
            val results = listOfNotNull(
                replies.append(stream.take(split)),
                replies.append(stream.drop(split))
            )
            assertEquals(listOf("value=1\n>"), results, "Split at $split")
        }
    }

    @Test
    fun `line buffer retains CRLF and does not emit a partial log`() {
        val lines = SerialLineBuffer()
        assertNull(lines.append("ODB RX value=1\r").firstOrNull())
        assertEquals(listOf("ODB RX value=1\r\n"), lines.append("\nRX 0x123"))
        assertEquals(listOf("RX 0x123 [1]: 00\n"), lines.append(" [1]: 00\n"))
    }
}
