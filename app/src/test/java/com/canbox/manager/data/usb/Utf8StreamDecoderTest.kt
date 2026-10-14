package com.canbox.manager.data.usb

import org.junit.Test
import kotlin.test.assertEquals

class Utf8StreamDecoderTest {
    @Test
    fun `preserves ODB units at every possible USB boundary`() {
        val original = "ODB RX pid=05 value=90 unit=°C latency=12ms\r\n"
        val bytes = original.toByteArray(Charsets.UTF_8)
        for (split in 0..bytes.size) {
            val decoder = Utf8StreamDecoder()
            val actual = decoder.decode(bytes.copyOfRange(0, split)) +
                decoder.decode(bytes.copyOfRange(split, bytes.size))
            assertEquals(original, actual, "USB split at byte $split")
        }
        val decoder = Utf8StreamDecoder()
        assertEquals(original, bytes.joinToString("") { decoder.decode(byteArrayOf(it)) })
    }

    @Test
    fun `disconnect clears a partial character before the next stream`() {
        val decoder = Utf8StreamDecoder()
        assertEquals("", decoder.decode(byteArrayOf(0xC2.toByte())))
        decoder.reset()
        assertEquals("RX 0x123 [0]:\n", decoder.decode("RX 0x123 [0]:\n".toByteArray()))
    }
}
