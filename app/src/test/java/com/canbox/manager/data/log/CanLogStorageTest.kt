package com.canbox.manager.data.log

import com.canbox.manager.data.usb.SerialLineBuffer
import com.canbox.manager.data.usb.Utf8StreamDecoder
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanLogStorageTest {

    private lateinit var directory: File
    private lateinit var storage: CanLogStorage

    @Before
    fun setUp() {
        directory = createTempDirectory("canbox-log").toFile()
        storage = CanLogStorage.fromDirectory(directory)
    }

    @After
    fun tearDown() {
        storage.close()
        directory.deleteRecursively()
    }

    @Test
    fun `session writes temp and atomically saves final while preserving temp`() {
        storage.open()
        val rawFrame = "RX 0x123 [2]: 01 AB\r\n"
        storage.writeRawFrame(rawFrame)

        val saved = storage.save()

        assertTrue(saved.isFile)
        assertEquals(rawFrame, saved.readText())
        assertTrue(storage.tempFile.isFile)
        assertEquals(rawFrame, storage.tempFile.readText())
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test
    fun `reported ESP RX line is emitted intact and exported at every USB split`() {
        val raw = "RX 0x180 [8]: 14 18 00 00 00 00 00 00 | t_ms=508 ext=0 rtr=0\r\n"
        for (split in 0..raw.length) {
            val buffer = SerialLineBuffer()
            val entries = (buffer.append(raw.take(split)) + buffer.append(raw.drop(split)))
                .mapNotNull(CanLogEntry::fromRawLine)
            assertEquals(listOf(raw), entries.map { it.rawLine }, "USB split at $split")
        }
        val entry = assertNotNull(CanLogEntry.fromRawLine(raw))
        storage.open()
        storage.writeRawFrame(entry.rawLine)
        val final = storage.save()
        assertContentEquals(raw.toByteArray(), storage.tempFile.readBytes())
        assertContentEquals(raw.toByteArray(), final.readBytes())
    }

    @Test
    fun `LOG ON entries retain all metadata in display source temp and final`() {
        // Synthetic fixtures: acceptance must not depend on firmware field layout.
        val lines = listOf(
            "RX 0x1ABCDE DLC=2 flags=EXT ts=123456 data=00 ff\r\n",
            "RX 0x7FE [2]: 00 ff\n",
            "RX 0180 [8]: 14 18 00 00 00 00 00 00 | t_ms=508 ext=0 rtr=0\r\n",
            "RX0x180 unknown format retained unchanged\n",
            "RX arbitrary content without valid CAN fields\n",
            "ODB TX command=01 0C queue=OK\r\n",
            "ODB RAW_RX 0x7E8 bytes=04 41 0C 1A F8\n",
            "ODB RX pid=05 ecu=0x7E8 value=90 unit=°C latency=12ms  \r\n",
            "ODB ERROR timeout\n",
            "ODB CAP supported=01,05\r\n",
            "ODB STATE ready\n",
            "ODB COUNTERS requests=4\n"
        )
        storage.open()
        val stream = lines.joinToString("") +
            "=== Live Vehicle Data ===\r\nRPM: 800\n=========================\r\n"
        val decoder = Utf8StreamDecoder()
        val buffer = SerialLineBuffer()
        val captured = mutableListOf<String>()
        stream.toByteArray(Charsets.UTF_8).forEach { byte ->
            buffer.append(decoder.decode(byteArrayOf(byte))).forEach { raw ->
                CanLogEntry.fromRawLine(raw)?.let { entry ->
                    captured.add(entry.rawLine)
                    storage.writeRawFrame(entry.rawLine)
                }
            }
        }
        assertEquals(lines, captured)
        val saved = storage.save()
        val expected = lines.joinToString("").toByteArray(Charsets.UTF_8)
        assertContentEquals(expected, storage.tempFile.readBytes())
        assertContentEquals(expected, saved.readBytes())
    }

    @Test
    fun `ODB containing CAN-like text stays raw and unrelated messages are excluded`() {
        val raw = "ODB RAW_RX 0x7E8 [2]: 41 00\r\n"
        val entry = assertNotNull(CanLogEntry.fromRawLine(raw))
        assertEquals(raw, entry.rawLine)
        listOf(
            "OK\r\n", "SYS INFO\n", "ERROR: RX 0x7E8 [2]: 41 00\n",
            "TX 0x123 [1]: 00\r\n", "OBD RX 0x123\n", "ODBX state\n",
            "=== Live Vehicle Data ===\n",
            "=========================\n"
        ).forEach {
            assertNull(CanLogEntry.fromRawLine(it))
        }
    }

    @Test
    fun `unclean session is preserved for recovery`() {
        storage.open()
        storage.writeRawFrame("RX 0x456 [1]: 00\n")
        storage.close(clean = false)

        val restarted = CanLogStorage.fromDirectory(directory)
        restarted.open()

        val recovered = directory.listFiles().orEmpty()
            .firstOrNull { it.name.startsWith("canlog_recovered_") }
        assertEquals("RX 0x456 [1]: 00\n", recovered?.readText())
        restarted.close()
    }

    @Test
    fun `clean session remains recoverable on next open`() {
        storage.open()
        storage.writeRawFrame("RX 0x100 [1]: 00\n")
        storage.close(clean = true)

        val restarted = CanLogStorage.fromDirectory(directory)
        restarted.open()

        assertEquals(
            1,
            directory.listFiles().orEmpty().count { it.name.startsWith("canlog_recovered_") }
        )
        restarted.close()
    }

    @Test
    fun `save works after reopening an unclean temp file`() {
        storage.open()
        storage.writeRawFrame("RX 0x789 [1]: 00\n")
        storage.close(clean = false)

        val restarted = CanLogStorage.fromDirectory(directory)
        val saved = restarted.save()

        assertEquals("RX 0x789 [1]: 00\n", saved.readText())
        restarted.close()
    }
}
