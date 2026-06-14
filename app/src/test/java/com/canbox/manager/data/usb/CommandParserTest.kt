package com.canbox.manager.data.usb

import org.junit.Assert.*
import org.junit.Test

class CommandParserTest {

    // ── isSuccess ──────────────────────────────────────────────────────────────

    @Test fun `isSuccess - bare OK line returns true`() {
        assertTrue(CommandParser.isSuccess("OK"))
    }

    @Test fun `isSuccess - OK with newlines returns true`() {
        assertTrue(CommandParser.isSuccess("\r\nOK\r\n"))
    }

    @Test fun `isSuccess - OK inside delimiter block returns true`() {
        assertTrue(CommandParser.isSuccess("=== Config ===\nsteerScale = 4\nOK"))
    }

    @Test fun `isSuccess - NOT OK must not match`() {
        assertFalse(CommandParser.isSuccess("NOT OK"))
    }

    @Test fun `isSuccess - ERROR NOT OK must not match`() {
        assertFalse(CommandParser.isSuccess("ERROR: NOT OK"))
    }

    @Test fun `isSuccess - word containing OK must not match`() {
        assertFalse(CommandParser.isSuccess("HOOK"))
        assertFalse(CommandParser.isSuccess("UNLOCK"))
        assertFalse(CommandParser.isSuccess("BOOK"))
    }

    @Test fun `isSuccess - empty string returns false`() {
        assertFalse(CommandParser.isSuccess(""))
    }

    @Test fun `isSuccess - error response returns false`() {
        assertFalse(CommandParser.isSuccess("ERROR: bad parameter"))
    }

    @Test fun `isSuccess - OK with trailing spaces returns true`() {
        assertTrue(CommandParser.isSuccess("OK  "))
        assertTrue(CommandParser.isSuccess("  OK"))
    }

    // ── isError ───────────────────────────────────────────────────────────────

    @Test fun `isError - ERROR line returns true`() {
        assertTrue(CommandParser.isError("ERROR: bad parameter"))
    }

    @Test fun `isError - OK response returns false`() {
        assertFalse(CommandParser.isError("OK"))
    }

    // ── getErrorMessage ───────────────────────────────────────────────────────

    @Test fun `getErrorMessage - extracts message after colon`() {
        assertEquals("bad parameter", CommandParser.getErrorMessage("ERROR: bad parameter"))
    }

    @Test fun `getErrorMessage - returns null for non-error`() {
        assertNull(CommandParser.getErrorMessage("OK"))
    }
}
