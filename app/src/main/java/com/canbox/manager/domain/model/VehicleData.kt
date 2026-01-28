package com.canbox.manager.domain.model

data class VehicleData(
    val rpm: Int = 0,
    val speed: Int = 0,
    val voltage: Float = 0f,
    val temperature: Int = 0,
    val fuelLevel: Int = 0,
    val dte: Int = 0,              // Distance To Empty (km)
    val steering: Int = 0,          // Steering angle in degrees (-500 to +500)
    val doors: DoorStatus = DoorStatus(),
    val lights: LightStatus = LightStatus(),
    val handbrake: Boolean = false,
    val reverse: Boolean = false,
    val mode: VehicleMode = VehicleMode.UNKNOWN,
    val configFile: String? = null  // Active CAN config file
)

data class DoorStatus(
    val frontLeft: Boolean = false,
    val frontRight: Boolean = false,
    val rearLeft: Boolean = false,
    val rearRight: Boolean = false,
    val trunk: Boolean = false
) {
    val anyOpen: Boolean
        get() = frontLeft || frontRight || rearLeft || rearRight || trunk
}

data class LightStatus(
    val parking: Boolean = false,    // Veilleuses
    val lowBeam: Boolean = false,    // Codes / Feux de croisement
    val highBeam: Boolean = false,   // Phares / Feux de route
    val leftIndicator: Boolean = false,
    val rightIndicator: Boolean = false,
    val hazard: Boolean = false,
    val fogFront: Boolean = false,
    val fogRear: Boolean = false
)

enum class VehicleMode {
    UNKNOWN,
    REAL,
    MOCK
}
