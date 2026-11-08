package com.canbox.manager.data.usb

import com.canbox.manager.domain.model.CalibrationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserCalibrationTest {
    // cfgList() in SerialCommand.cpp uses println for framing and printf for fields.
    private val espResponse = "=== Current Configuration ===\r\n" +
        "steerOffset  = -125    (center offset)\n" +
        "steerInvert  = 1    (invert direction)\n" +
        "steerScale   = 425    (scale x0.0001)\n" +
        "indTimeout   = 750    (indicator ms)\n" +
        "rpmDiv       = 9    (RPM divisor)\n" +
        "tankCap      = 60    (tank liters)\n" +
        "dteDiv       = 320    (DTE divisor x100)\n" +
        "=============================\r\n"

    private val expected = CalibrationConfig(
        steeringOffset = -125,
        steeringScale = 425,
        steeringInvert = true,
        indicatorTimeout = 750,
        rpmDivisor = 9,
        tankCapacity = 60,
        dteDivisor = 320
    )

    private val ranges = mapOf(
        "steerOffset" to CalibrationConfig.STEERING_OFFSET_RANGE,
        "steerScale" to CalibrationConfig.STEERING_SCALE_RANGE,
        "indTimeout" to CalibrationConfig.INDICATOR_TIMEOUT_RANGE,
        "rpmDiv" to CalibrationConfig.RPM_DIVISOR_RANGE,
        "tankCap" to CalibrationConfig.TANK_CAPACITY_RANGE,
        "dteDiv" to CalibrationConfig.DTE_DIVISOR_RANGE
    )
    private val required = ranges.keys + "steerInvert"

    private fun replacing(name: String, value: String): String =
        espResponse.lines().joinToString("\n") { line ->
            if (line.substringBefore('=').trim() == name) "$name = $value" else line
        }

    private fun assertRejected(response: String, vararg messageParts: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CommandParser.parseCfgList(response)
        }
        for (part in listOf("CFG LIST") + messageParts) {
            assertTrue("Expected '$part' in '${error.message}'", error.message.orEmpty().contains(part))
        }
    }

    @Test fun `parses real ESP response without OK and preserves all values`() {
        assertEquals(expected, CommandParser.parseCfgList(espResponse))
    }

    @Test fun `accepts LF and CRLF with whitespace and optional OK`() {
        for (newline in listOf("\n", "\r\n")) {
            val response = espResponse.lines().joinToString(newline) { "\t$it  " }
            assertEquals(expected, CommandParser.parseCfgList("CFG LIST$newline$response${newline}OK"))
        }
        assertEquals(expected, CommandParser.parseCfgList(replacing("steerScale", "\t425\t(scale x0.0001)")))
    }

    @Test fun `accepts reordered parameters without descriptions or framing`() {
        val response = espResponse.lines().filter { it.substringBefore('=').trim() in required }
            .reversed().joinToString("\n") { it.substringBefore('(').trim() }
        assertEquals(expected, CommandParser.parseCfgList(response))
    }

    @Test fun `accepts unknown fields without interpreting their values`() {
        assertEquals(expected, CommandParser.parseCfgList(
            espResponse + "futureMode = automatic\nfutureRatio = 0.25 (ratio)\n"
        ))
    }

    @Test fun `rejects each missing required parameter`() {
        for (name in required) {
            val response = espResponse.lines()
                .filterNot { it.substringBefore('=').trim() == name }.joinToString("\n")
            assertRejected(response, "missing", name)
        }
    }

    @Test fun `rejects empty unrelated and truncated responses`() {
        for (response in listOf("", " \r\n\t", "OK", "futureMode = automatic",
            "=== Current Configuration ===\n=============================",
            espResponse.substringBefore("tankCap"))) {
            assertRejected(response, "missing", "tankCap", "dteDiv")
        }
        assertRejected("Firmware: 1.7.0", "invalid")
    }

    @Test fun `rejects ERROR even alongside a complete valid response`() {
        for (error in listOf("ERROR", "  ERROR: Unable to read configuration")) {
            assertRejected(error, "ERROR")
            assertRejected("$error\n$espResponse", "ERROR")
            assertRejected("${espResponse}$error\nOK", "ERROR")
        }
    }

    @Test fun `rejects malformed values for every required parameter`() {
        val invalidValues = listOf(
            "", "abc", "1.5", "0x01", "NaN", "1 garbage", "1 2", "1=2",
            "1 (unterminated", "1 (description) garbage", "1 (first) (second)",
            "2147483648", "-2147483649", "999999999999999999999999999999"
        )
        for (name in required) {
            for (value in invalidValues) {
                assertRejected(replacing(name, value), name, "invalid")
            }
        }
    }

    @Test fun `rejects malformed parameter lines even after valid fields`() {
        for (line in listOf("rpmDiv", "rpmDiv: 9", "rpmDiv 9", "= 9")) {
            assertRejected(espResponse + line, "invalid", line)
        }
    }

    @Test fun `requires boolean to be exactly zero or one`() {
        assertEquals(expected.copy(steeringInvert = false), CommandParser.parseCfgList(replacing("steerInvert", "0")))
        assertEquals(expected, CommandParser.parseCfgList(replacing("steerInvert", "1")))
        for (value in listOf("true", "false", "-1", "2", "00", "01", "+1", "-0")) {
            assertRejected(replacing("steerInvert", value), "steerInvert", "invalid")
        }
    }

    @Test fun `rejects identical and conflicting duplicate parameters`() {
        for (name in required) {
            val original = espResponse.lines().first { it.substringBefore('=').trim() == name }
            assertRejected(espResponse + original, "duplicate", name)
            assertRejected(espResponse + "$name = 0", "duplicate", name)
        }
    }

    @Test fun `accepts inclusive CalibrationConfig boundaries`() {
        for (upper in listOf(false, true)) {
            val bounds = ranges.mapValues { (_, range) -> if (upper) range.last else range.first }
            val response = bounds.entries.joinToString("\n") { (name, value) -> "$name = $value" } +
                "\nsteerInvert = ${if (upper) 1 else 0}"
            assertEquals(CalibrationConfig(
                steeringOffset = bounds.getValue("steerOffset"),
                steeringScale = bounds.getValue("steerScale"),
                steeringInvert = upper,
                indicatorTimeout = bounds.getValue("indTimeout"),
                rpmDivisor = bounds.getValue("rpmDiv"),
                tankCapacity = bounds.getValue("tankCap"),
                dteDivisor = bounds.getValue("dteDiv")
            ), CommandParser.parseCfgList(response))
        }
    }

    @Test fun `rejects values just outside every CalibrationConfig range`() {
        for ((name, range) in ranges) {
            for (value in listOf(range.first - 1, range.last + 1, Int.MIN_VALUE, Int.MAX_VALUE)) {
                assertRejected(replacing(name, value.toString()), name, "out of range", range.toString())
            }
        }
    }
}
