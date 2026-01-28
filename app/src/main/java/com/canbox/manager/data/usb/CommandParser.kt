package com.canbox.manager.data.usb

import com.canbox.manager.domain.model.*

object CommandParser {

    /**
     * Parse SYS INFO response
     * Example response:
     * ESP32 CANBox v1.7.0
     * Build: Jan 26 2026
     * Chip: ESP32-S3
     * Free heap: 123456
     * Uptime: 12345s
     * OK
     */
    fun parseSysInfo(response: String): FirmwareInfo {
        val lines = response.lines()
        var version = "Unknown"
        var buildDate = ""
        var chipModel = ""
        var freeHeap = 0
        var uptime = 0L

        for (line in lines) {
            when {
                line.startsWith("ESP32 CANBox v") -> {
                    version = line.substringAfter("v").trim()
                }
                line.startsWith("Build:") -> {
                    buildDate = line.substringAfter("Build:").trim()
                }
                line.startsWith("Chip:") -> {
                    chipModel = line.substringAfter("Chip:").trim()
                }
                line.startsWith("Free heap:") -> {
                    freeHeap = line.substringAfter("Free heap:").trim().toIntOrNull() ?: 0
                }
                line.startsWith("Uptime:") -> {
                    val uptimeStr = line.substringAfter("Uptime:").trim().removeSuffix("s")
                    uptime = uptimeStr.toLongOrNull() ?: 0
                }
            }
        }

        return FirmwareInfo(
            version = version,
            buildDate = buildDate,
            chipModel = chipModel,
            freeHeap = freeHeap,
            uptime = uptime
        )
    }

    /**
     * Parse SYS DATA response for vehicle data
     * Example response from ESP32:
     * === Live Vehicle Data ===
     * Config:   MockDemo.json
     * Mode:     MOCK (Mock Demo)
     * RPM:      4300
     * Speed:    4 km/h
     * Steering: 2000
     * Fuel:     30 L
     * Battery:  13.8 V
     * DTE:      350 km
     * Temp:     87 C
     * Doors:    0x00
     * Lights:   H=1 P=0 HB=0 L=0 R=0
     * =========================
     */
    fun parseSysData(response: String): VehicleData {
        val lines = response.lines()
        var rpm = 0
        var speed = 0
        var voltage = 0f
        var temperature = 0
        var fuelLevel = 0
        var dte = 0
        var steering = 0
        var doorsValue = 0
        var lights = LightStatus()
        var handbrake = false
        var reverse = false
        var mode = VehicleMode.UNKNOWN
        var configFile: String? = null

        for (line in lines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex == -1) continue

            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()

            when (key) {
                "Config" -> configFile = value.ifEmpty { null }
                "RPM" -> rpm = extractFirstInt(value)
                "Speed" -> speed = extractFirstInt(value)
                "Voltage", "Battery" -> voltage = extractFirstFloat(value)
                "Temp" -> temperature = extractFirstInt(value)
                "Fuel" -> fuelLevel = extractFirstInt(value)
                "DTE" -> dte = extractFirstInt(value)
                "Steering" -> steering = extractFirstInt(value)
                "Doors" -> doorsValue = parseHexOrInt(value)
                "Lights" -> lights = parseLightsString(value)
                "Handbrake" -> handbrake = value == "1"
                "Reverse" -> reverse = value == "1"
                "Mode" -> {
                    // Extract first word only: "MOCK (Mock Demo)" -> "MOCK"
                    val modeWord = value.split(" ", "(").first().trim().uppercase()
                    mode = when (modeWord) {
                        "REAL" -> VehicleMode.REAL
                        "MOCK" -> VehicleMode.MOCK
                        else -> VehicleMode.UNKNOWN
                    }
                }
            }
        }

        return VehicleData(
            rpm = rpm,
            speed = speed,
            voltage = voltage,
            temperature = temperature,
            fuelLevel = fuelLevel,
            dte = dte,
            steering = steering,
            doors = parseDoorStatus(doorsValue),
            lights = lights,
            handbrake = handbrake,
            reverse = reverse,
            mode = mode,
            configFile = configFile
        )
    }

    /**
     * Extract the first integer from a string like "4 km/h" or "30 L"
     */
    private fun extractFirstInt(value: String): Int {
        return value.split(" ").firstOrNull()?.toIntOrNull() ?: 0
    }

    /**
     * Extract the first float from a string like "13.8 V"
     */
    private fun extractFirstFloat(value: String): Float {
        return value.split(" ").firstOrNull()?.toFloatOrNull() ?: 0f
    }

    /**
     * Parse lights string format: "H=1 P=0 HB=0 L=0 R=0"
     * H=Hazard, P=Parking, HB=HighBeam, L=Left, R=Right
     * Also supports hex format: "0x04"
     */
    private fun parseLightsString(value: String): LightStatus {
        // Check if it's hex format first
        if (value.startsWith("0x") || value.startsWith("0X")) {
            return parseLightStatus(parseHexOrInt(value))
        }

        // Parse key=value format: "H=1 P=0 HB=0 L=0 R=0"
        val parts = value.split(" ")
        var hazard = false
        var parking = false
        var highBeam = false
        var lowBeam = false
        var leftIndicator = false
        var rightIndicator = false

        for (part in parts) {
            val kv = part.split("=")
            if (kv.size != 2) continue
            val isOn = kv[1] == "1"
            when (kv[0]) {
                "H" -> hazard = isOn
                "P" -> parking = isOn
                "HB" -> highBeam = isOn
                "LB" -> lowBeam = isOn
                "L" -> leftIndicator = isOn
                "R" -> rightIndicator = isOn
            }
        }

        return LightStatus(
            parking = parking,
            lowBeam = lowBeam,
            highBeam = highBeam,
            leftIndicator = leftIndicator,
            rightIndicator = rightIndicator,
            hazard = hazard
        )
    }

    /**
     * Parse CFG LIST response
     * Example from ESP32:
     * === Current Configuration ===
     * steerOffset  = 100    (center offset)
     * steerInvert  = 1    (invert direction)
     * steerScale   = 4    (scale x0.01)
     * indTimeout   = 500    (indicator ms)
     * rpmDiv       = 7    (RPM divisor)
     * tankCap      = 45    (tank liters)
     * dteDiv       = 283    (DTE divisor x100)
     * =============================
     */
    fun parseCfgList(response: String): CalibrationConfig {
        val lines = response.lines()
        var steeringOffset = 0
        var steeringScale = 100
        var steeringInvert = false
        var indicatorTimeout = 500
        var rpmDivisor = 7
        var tankCapacity = 45
        var dteDivisor = 283

        for (line in lines) {
            // Parse format: "paramName = value (description)"
            val equalsIndex = line.indexOf('=')
            if (equalsIndex == -1) continue

            val paramName = line.substring(0, equalsIndex).trim()
            val valueAndDesc = line.substring(equalsIndex + 1).trim()
            // Extract just the number before any description in parentheses
            val valueStr = valueAndDesc.split(" ", "(").first().trim()

            when (paramName) {
                "steerOffset" -> steeringOffset = valueStr.toIntOrNull() ?: 0
                "steerScale" -> steeringScale = valueStr.toIntOrNull() ?: 100
                "steerInvert" -> steeringInvert = valueStr == "1"
                "indTimeout" -> indicatorTimeout = valueStr.toIntOrNull() ?: 500
                "rpmDiv" -> rpmDivisor = valueStr.toIntOrNull() ?: 7
                "tankCap" -> tankCapacity = valueStr.toIntOrNull() ?: 45
                "dteDiv" -> dteDivisor = valueStr.toIntOrNull() ?: 283
            }
        }

        return CalibrationConfig(
            steeringOffset = steeringOffset,
            steeringScale = steeringScale,
            steeringInvert = steeringInvert,
            indicatorTimeout = indicatorTimeout,
            rpmDivisor = rpmDivisor,
            tankCapacity = tankCapacity,
            dteDivisor = dteDivisor
        )
    }

    /**
     * Parse CAN LIST response
     * Example:
     * NissanJukeF15.json (2048 bytes)
     * MockDemo.json (512 bytes)
     * OK
     */
    fun parseCanList(response: String): List<CanConfigFile> {
        val files = mutableListOf<CanConfigFile>()
        val regex = Regex("""(.+\.json)\s*\((\d+)\s*bytes?\)""")

        for (line in response.lines()) {
            regex.find(line)?.let { match ->
                files.add(
                    CanConfigFile(
                        filename = match.groupValues[1].trim(),
                        size = match.groupValues[2].toInt()
                    )
                )
            }
        }

        return files
    }

    /**
     * Parse CAN STATUS response
     * Example from ESP32:
     * === CAN Configuration Status ===
     * Config: MockDemo.json
     * Mode: MOCK (simulated data)
     * Profile: Mock Demo
     * Frames processed: 0
     * Unknown frames: 0
     * ================================
     */
    fun parseCanStatus(response: String): Pair<String?, VehicleMode> {
        var activeConfig: String? = null
        var mode = VehicleMode.UNKNOWN

        for (line in response.lines()) {
            when {
                // Config: has priority (contains the actual filename)
                line.startsWith("Config:") -> {
                    val value = line.substringAfter(":").trim()
                    if (value.isNotEmpty() && value.lowercase() != "none") {
                        activeConfig = value
                    }
                }
                // Fallback to Active: or Profile: if Config: not present
                (line.startsWith("Active:") || line.startsWith("Profile:")) && activeConfig == null -> {
                    val value = line.substringAfter(":").trim()
                    activeConfig = if (value.isNotEmpty() && value.lowercase() != "none") value else null
                }
                line.startsWith("Mode:") -> {
                    // Extract first word only: "MOCK (simulated data)" -> "MOCK"
                    val modeWord = line.substringAfter("Mode:").trim()
                        .split(" ", "(").first().trim().uppercase()
                    mode = when (modeWord) {
                        "REAL" -> VehicleMode.REAL
                        "MOCK" -> VehicleMode.MOCK
                        else -> VehicleMode.UNKNOWN
                    }
                }
            }
        }

        return activeConfig to mode
    }

    /**
     * Parse CAN log frames
     * Example: RX 0x002 [8]: 00 0B 60 00 00 00 00 00
     */
    fun parseCanFrame(line: String, timestamp: Long = System.currentTimeMillis()): CanFrame? {
        val regex = Regex("""(RX|TX)\s+0x([0-9A-Fa-f]+)\s+\[(\d+)\]:\s*(.*)""")
        val match = regex.find(line) ?: return null

        val direction = when (match.groupValues[1]) {
            "RX" -> FrameDirection.RX
            "TX" -> FrameDirection.TX
            else -> return null
        }

        val canId = match.groupValues[2].toIntOrNull(16) ?: return null
        val dlc = match.groupValues[3].toIntOrNull() ?: return null
        val dataStr = match.groupValues[4].trim()

        val data = dataStr.split(" ")
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull(16)?.toByte() }
            .toByteArray()

        return CanFrame(
            timestamp = timestamp,
            direction = direction,
            canId = canId,
            dlc = dlc,
            data = data
        )
    }

    private fun parseHexOrInt(value: String): Int {
        return if (value.startsWith("0x") || value.startsWith("0X")) {
            value.substring(2).toIntOrNull(16) ?: 0
        } else {
            value.toIntOrNull() ?: 0
        }
    }

    private fun parseDoorStatus(value: Int): DoorStatus {
        return DoorStatus(
            frontLeft = (value and 0x01) != 0,
            frontRight = (value and 0x02) != 0,
            rearLeft = (value and 0x04) != 0,
            rearRight = (value and 0x08) != 0,
            trunk = (value and 0x10) != 0
        )
    }

    private fun parseLightStatus(value: Int): LightStatus {
        return LightStatus(
            parking = (value and 0x01) != 0,
            lowBeam = (value and 0x02) != 0,
            highBeam = (value and 0x04) != 0,
            leftIndicator = (value and 0x08) != 0,
            rightIndicator = (value and 0x10) != 0,
            hazard = (value and 0x20) != 0,
            fogFront = (value and 0x40) != 0,
            fogRear = (value and 0x80) != 0
        )
    }

    fun isSuccess(response: String): Boolean {
        return response.contains("OK")
    }

    fun isError(response: String): Boolean {
        return response.contains("ERROR")
    }

    fun getErrorMessage(response: String): String? {
        if (!isError(response)) return null
        val errorLine = response.lines().find { it.contains("ERROR") }
        return errorLine?.substringAfter("ERROR")?.trim()?.removePrefix(":")?.trim()
    }
}
