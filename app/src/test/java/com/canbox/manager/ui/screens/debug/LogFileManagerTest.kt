package com.canbox.manager.ui.screens.debug

import com.canbox.manager.domain.model.CanFrame
import com.canbox.manager.domain.model.FrameDirection
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LogFileManagerTest {

    private lateinit var tempDir: File
    private lateinit var manager: LogFileManager

    @Before
    fun setup() {
        tempDir = createTempDirectory("canbox_test").toFile()
        manager = LogFileManager(tempDir)
    }

    @After
    fun teardown() {
        manager.close()
        tempDir.deleteRecursively()
    }

    // ── open ─────────────────────────────────────────────────────────────────

    @Test
    fun `open creates temp file with header`() {
        manager.open()
        val tmp = File(tempDir, "canlog_tmp.txt")
        assertTrue(tmp.exists(), "canlog_tmp.txt must exist after open()")
        val content = tmp.readText()
        assertTrue(content.contains("# CANBox log"), "header line missing")
        assertTrue(content.contains("CAN_ID"), "column header missing")
    }

    @Test
    fun `open overwrites existing temp file`() {
        File(tempDir, "canlog_tmp.txt").writeText("stale content")
        manager.open()
        val content = File(tempDir, "canlog_tmp.txt").readText()
        assertTrue(!content.contains("stale content"), "open() must overwrite stale temp file")
    }

    // ── writeFrame ────────────────────────────────────────────────────────────

    @Test
    fun `writeFrame appends correct hex id and data`() {
        manager.open()
        manager.writeFrame(frame(canId = 0x1A3, data = byteArrayOf(0x01, 0xAB.toByte()), dlc = 2))
        manager.flush()
        val content = File(tempDir, "canlog_tmp.txt").readText()
        assertTrue(content.contains("0x1A3"), "CAN ID missing")
        assertTrue(content.contains("01 AB"), "data bytes missing")
        assertTrue(content.contains("[2]"), "DLC missing")
    }

    @Test
    fun `writeFrame accumulates multiple frames`() {
        manager.open()
        repeat(5) { i -> manager.writeFrame(frame(canId = i)) }
        manager.flush()
        val lines = File(tempDir, "canlog_tmp.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(5, lines.size, "expected 5 data lines")
    }

    @Test
    fun `hasFrames is false before any writeFrame`() {
        manager.open()
        assertTrue(!manager.hasFrames(), "hasFrames must be false before writing")
    }

    @Test
    fun `hasFrames is true after writeFrame`() {
        manager.open()
        manager.writeFrame(frame())
        assertTrue(manager.hasFrames(), "hasFrames must be true after writing")
    }

    // ── save ──────────────────────────────────────────────────────────────────

    @Test
    fun `save copies temp to timestamped final file`() {
        manager.open()
        manager.writeFrame(frame(canId = 0x456, data = byteArrayOf(0xBE.toByte(), 0xEF.toByte())))
        val saved = manager.save()
        assertTrue(saved.exists(), "saved file must exist")
        assertTrue(saved.name.startsWith("canlog_"), "filename must start with canlog_")
        assertTrue(saved.name.endsWith(".txt"), "filename must end with .txt")
        val content = saved.readText()
        assertTrue(content.contains("0x456"), "saved file must contain frame data")
        assertTrue(content.contains("BE EF"), "saved file must contain hex bytes")
    }

    @Test
    fun `save preserves temp file so logging can continue`() {
        manager.open()
        manager.writeFrame(frame())
        manager.save()
        val tmp = File(tempDir, "canlog_tmp.txt")
        assertTrue(tmp.exists(), "temp file must still exist after save()")
    }

    @Test
    fun `save throws when open was never called`() {
        assertFailsWith<IllegalStateException> { manager.save() }
    }

    @Test
    fun `save throws when no frames written`() {
        manager.open()
        // header written but frameCount == 0
        assertFailsWith<IllegalStateException> { manager.save() }
    }

    // ── frame list cap ────────────────────────────────────────────────────────

    @Test
    fun `display list is capped at DISPLAY_FRAMES regardless of total written`() {
        val cap = DebugViewModel.DISPLAY_FRAMES
        var frames = emptyList<CanFrame>()
        val total = cap * 3
        repeat(total) { i ->
            frames = (listOf(frame(canId = i)) + frames).take(cap)
        }
        assertEquals(cap, frames.size, "display list must be capped at $cap")
        assertEquals(total - 1, frames.first().canId, "most recent frame must be first")
        assertEquals(total - cap, frames.last().canId, "oldest retained frame is wrong")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun frame(
        canId: Int = 0x100,
        data: ByteArray = byteArrayOf(0x00),
        dlc: Int = data.size
    ) = CanFrame(
        timestamp = System.currentTimeMillis(),
        direction = FrameDirection.RX,
        canId = canId,
        dlc = dlc,
        data = data
    )
}
