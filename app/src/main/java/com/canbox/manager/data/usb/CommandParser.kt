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
     * Example response:
     * RPM: 2500
     * Speed: 60
     * Voltage: 14.2
     * Temp: 85
     * Fuel: 30
     * DTE: 350
     * Steering: -150
     * Doors: 0x01
     * Lights: 0x04
     * Handbrake: 0
     * Reverse: 0
     * Mode: REAL
     * OK
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
        var lightsValue = 0
        var handbrake = false
        var reverse = false
        var mode = VehicleMode.UNKNOWN

        for (line in lines) {
            val parts = line.split(":").map { it.trim() }
            if (parts.size != 2) continue

            when (parts[0]) {
                "RPM" -> rpm = parts[1].toIntOrNull() ?: 0
                "Speed" -> speed = parts[1].toIntOrNull() ?: 0
                "Voltage" -> voltage = parts[1].toFloatOrNull() ?: 0f
                "Temp" -> temperature = parts[1].toIntOrNull() ?: 0
                "Fuel" -> fuelLevel = parts[1].toIntOrNull() ?: 0
                "DTE" -> dte = parts[1].toIntOrNull() ?: 0
                "Steering" -> steering = parts[1].toIntOrNull() ?: 0
                "Doors" -> doorsValue = parseHexOrInt(parts[1])
                "Lights" -> lightsValue = parseHexOrInt(parts[1])
                "Handbrake" -> handbrake = parts[1] == "1"
                "Reverse" -> reverse = parts[1] == "1"
                "Mode" -> mode = when (parts[1].uppercase()) {
                    "REAL" -> VehicleMode.REAL
                    "MOCK" -> VehicleMode.MOCK
                    else -> VehicleMode.UNKNOWN
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
            lights = parseLightStatus(lightsValue),
            handbrake = handbrake,
            reverse = reverse,
            mode = mode
        )
    }

    /**
     * Parse CFG LIST response
     * Example:
     * steer_offset: 100
     * steer_scale: 4
     * steer_invert: 1
     * indicator_timeout: 500
     * rpm_divisor: 7
     * tank_capacity: 45
     * dte_divisor: 283
     * OK
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
            val parts = line.split(":").map { it.trim() }
            if (parts.size != 2) continue

            when (parts[0]) {
                "steer_offset" -> steeringOffset = parts[1].toIntOrNull() ?: 0
                "steer_scale" -> steeringScale = parts[1].toIntOrNull() ?: 100
                "steer_invert" -> steeringInvert = parts[1] == "1"
                "indicator_timeout" -> indicatorTimeout = parts[1].toIntOrNull() ?: 500
                "rpm_divisor" -> rpmDivisor = parts[1].toIntOrNull() ?: 7
                "tank_capacity" -> tankCapacity = parts[1].toIntOrNull() ?: 45
                "dte_divisor" -> dteDivisor = parts[1].toIntOrNull() ?: 283
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
     * Example:
     * Active: NissanJukeF15.json
     * Mode: REAL
     * OK
     */
    fun parseCanStatus(response: String): Pair<String?, VehicleMode> {
        var activeConfig: String? = null
        var mode = VehicleMode.UNKNOWN

        for (line in response.lines()) {
            when {
                line.startsWith("Active:") -> {
                    val value = line.substringAfter("Active:").trim()
                    activeConfig = if (value.isNotEmpty() && value != "none") value else null
                }
                line.startsWith("Mode:") -> {
                    mode = when (line.substringAfter("Mode:").trim().uppercase()) {
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
