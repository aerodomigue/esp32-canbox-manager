package com.canbox.manager.data.usb

import com.canbox.manager.domain.model.LightStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandParserLightsTest {
    private fun parse(value: String) = CommandParser.parseSysData(
        "=== Live Vehicle Data ===\r\nLights:   $value\r\n=========================\r\n"
    ).lights

    @Test fun `reported example - all lights off`() {
        assertEquals(LightStatus(), parse("H=0 P=0 HB=0 L=0 R=0"))
    }

    @Test fun `reported example - parking lights only`() {
        assertEquals(LightStatus(parking = true), parse("H=0 P=1 HB=0 L=0 R=0"))
    }

    @Test fun `reported example - low beam and parking lights`() {
        assertEquals(
            LightStatus(parking = true, lowBeam = true),
            parse("H=1 P=1 HB=0 L=0 R=0")
        )
    }

    @Test fun `H is low beam and never hazard`() {
        assertEquals(LightStatus(lowBeam = true), parse("H=1 P=0 HB=0 L=0 R=0"))
    }

    @Test fun `HB is independent from low beam`() {
        assertEquals(LightStatus(highBeam = true), parse("H=0 P=0 HB=1 L=0 R=0"))
    }

    @Test fun `all firmware light combinations map independently`() {
        for (bits in 0..31) {
            val h = bits and 1
            val p = (bits shr 1) and 1
            val hb = (bits shr 2) and 1
            val l = (bits shr 3) and 1
            val r = (bits shr 4) and 1
            val line = "H=$h P=$p HB=$hb L=$l R=$r"
            assertEquals(line, LightStatus(
                lowBeam = h == 1,
                parking = p == 1,
                highBeam = hb == 1,
                leftIndicator = l == 1,
                rightIndicator = r == 1
            ), parse(line))
        }
    }

    @Test fun `successive dashboards do not retain a previous light state`() {
        parse("H=1 P=1 HB=1 L=1 R=1")
        assertEquals(LightStatus(), parse("H=0 P=0 HB=0 L=0 R=0"))
    }

    @Test fun `legacy hexadecimal light mask remains unchanged`() {
        assertEquals(LightStatus(lowBeam = true), parse("0x02"))
        assertEquals(LightStatus(hazard = true), parse("0x20"))
    }

    @Test fun `previous LB alias remains supported`() {
        assertEquals(LightStatus(lowBeam = true), parse("LB=1 P=0 HB=0 L=0 R=0"))
        assertEquals(LightStatus(), parse("LB=0 P=0 HB=0 L=0 R=0"))
    }
}
