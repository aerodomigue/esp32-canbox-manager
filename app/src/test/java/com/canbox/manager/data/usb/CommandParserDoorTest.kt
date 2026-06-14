package com.canbox.manager.data.usb

import org.junit.Assert.*
import org.junit.Test

class CommandParserDoorTest {

    // Firmware bitmask (CanConfigProcessor.cpp):
    // DOOR_DRIVER    0x80  bit 7
    // DOOR_PASSENGER 0x40  bit 6
    // DOOR_REAR_LEFT 0x20  bit 5
    // DOOR_REAR_RIGHT 0x10 bit 4
    // DOOR_BOOT      0x08  bit 3

    private fun parse(hex: String) = CommandParser.parseSysData(
        "=== Live Vehicle Data ===\nDoors:    $hex\n========================="
    ).doors

    @Test fun `all closed`() {
        val d = parse("0x00")
        assertFalse(d.frontLeft)
        assertFalse(d.frontRight)
        assertFalse(d.rearLeft)
        assertFalse(d.rearRight)
        assertFalse(d.trunk)
        assertFalse(d.anyOpen)
    }

    @Test fun `driver door open - 0x80`() {
        val d = parse("0x80")
        assertTrue(d.frontLeft)
        assertFalse(d.frontRight)
        assertFalse(d.rearLeft)
        assertFalse(d.rearRight)
        assertFalse(d.trunk)
    }

    @Test fun `passenger door open - 0x40`() {
        val d = parse("0x40")
        assertFalse(d.frontLeft)
        assertTrue(d.frontRight)
        assertFalse(d.rearLeft)
        assertFalse(d.rearRight)
        assertFalse(d.trunk)
    }

    @Test fun `rear left open - 0x20`() {
        val d = parse("0x20")
        assertFalse(d.frontLeft)
        assertFalse(d.frontRight)
        assertTrue(d.rearLeft)
        assertFalse(d.rearRight)
        assertFalse(d.trunk)
    }

    @Test fun `rear right open - 0x10`() {
        val d = parse("0x10")
        assertFalse(d.frontLeft)
        assertFalse(d.frontRight)
        assertFalse(d.rearLeft)
        assertTrue(d.rearRight)
        assertFalse(d.trunk)
    }

    @Test fun `boot open - 0x08`() {
        val d = parse("0x08")
        assertFalse(d.frontLeft)
        assertFalse(d.frontRight)
        assertFalse(d.rearLeft)
        assertFalse(d.rearRight)
        assertTrue(d.trunk)
    }

    @Test fun `all doors open - 0xF8`() {
        val d = parse("0xF8")
        assertTrue(d.frontLeft)
        assertTrue(d.frontRight)
        assertTrue(d.rearLeft)
        assertTrue(d.rearRight)
        assertTrue(d.trunk)
        assertTrue(d.anyOpen)
    }

    @Test fun `driver and boot open - 0x88`() {
        val d = parse("0x88")
        assertTrue(d.frontLeft)
        assertFalse(d.frontRight)
        assertFalse(d.rearLeft)
        assertFalse(d.rearRight)
        assertTrue(d.trunk)
    }
}
