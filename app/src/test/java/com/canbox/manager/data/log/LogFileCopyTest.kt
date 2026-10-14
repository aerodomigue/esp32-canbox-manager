package com.canbox.manager.data.log

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.*

class LogFileCopyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `copies complete file without parsing or display limit`() {
        val source = temporary.newFile("canlog.txt")
        val bytes = (buildString {
            repeat(1000) {
                append("RX 0x180 [8]: 14 18 00 00 00 00 00 00 | t_ms=$it ext=0 rtr=0\r\n")
                append("ODB RX pid=05 unit=°C  \n")
                append("OTHER original line $it\r\n")
            }
            append("unfinished original tail")
        }).toByteArray()
        source.writeBytes(bytes)
        val directory = temporary.newFolder("usb")
        val output = LogFileCopy.copy(source, directory)
        assertContentEquals(bytes, output.readBytes())
        assertContentEquals(bytes, source.readBytes())
        assertEquals(listOf(output), directory.listFiles()!!.toList())
    }

    @Test fun `repeated exports never overwrite earlier files`() {
        val source = temporary.newFile("canlog.txt").apply { writeText("RX first\n") }
        val directory = temporary.newFolder("usb")
        val first = LogFileCopy.copy(source, directory)
        source.writeText("ODB second\n")
        val second = LogFileCopy.copy(source, directory)
        assertNotEquals(first, second)
        assertEquals("RX first\n", first.readText())
        assertEquals("ODB second\n", second.readText())
    }

    @Test fun `removal during copy keeps local file and never publishes partial export`() {
        val source = temporary.newFile("canlog.txt").apply { writeBytes(ByteArray(200_000) { 65 }) }
        val directory = temporary.newFolder("usb")
        var checks = 0
        assertFailsWith<IOException> {
            LogFileCopy.copy(source, directory) {
                if (++checks == 3) throw IOException("USB disconnected")
            }
        }
        assertEquals(200_000L, source.length())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `failed destination preserves original`() {
        val source = temporary.newFile("canlog.txt").apply { writeText("RX data\n") }
        val blocked = temporary.newFile("not-a-directory")
        assertFailsWith<IOException> { LogFileCopy.copy(source, File(blocked, "logs")) }
        assertEquals("RX data\n", source.readText())
    }

    @Test fun `unavailable USB does not create a fallback directory`() {
        val source = temporary.newFile("canlog.txt").apply { writeText("RX data\n") }
        val directory = File(temporary.root, "absent-usb/logs")
        assertFailsWith<IOException> {
            LogFileCopy.copy(source, directory) { throw IOException("Not mounted") }
        }
        assertFalse(directory.exists())
        assertEquals("RX data\n", source.readText())
    }

    @Test fun `corrupted USB staging file fails verification and keeps source`() {
        val source = temporary.newFile("canlog.txt").apply { writeText("RX original\n") }
        val directory = temporary.newFolder("usb")
        var checks = 0
        assertFailsWith<IOException> {
            LogFileCopy.copy(source, directory) {
                // Before read-back verification, corrupt one byte without changing length.
                if (++checks == 4) {
                    val staging = directory.listFiles()!!.single()
                    val bytes = staging.readBytes()
                    bytes[0] = 0
                    staging.writeBytes(bytes)
                }
            }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
        assertEquals("RX original\n", source.readText())
    }

    @Test fun `export uses stable snapshot while capture continues`() {
        val storage = CanLogStorage(temporary.newFolder("capture"))
        try {
            storage.open()
            storage.writeRawFrame("RX first\r\nODB TX first\n")
            val snapshot = storage.save()
            storage.writeRawFrame("RX later\n")
            val output = LogFileCopy.copy(snapshot, temporary.newFolder("usb"))
            assertEquals("RX first\r\nODB TX first\n", output.readText())
            storage.flush()
            assertTrue(storage.tempFile.readText().endsWith("RX later\n"))
        } finally {
            storage.close()
        }
    }
}
