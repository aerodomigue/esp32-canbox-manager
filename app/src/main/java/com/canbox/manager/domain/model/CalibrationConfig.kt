package com.canbox.manager.domain.model

data class CalibrationConfig(
    val steeringOffset: Int = 0,
    val steeringScale: Int = 300,
    val steeringInvert: Boolean = false,
    val indicatorTimeout: Int = 500,
    val rpmDivisor: Int = 7,
    val tankCapacity: Int = 45,
    val dteDivisor: Int = 283
) {
    companion object {
        // Parameter ranges for validation
        val STEERING_OFFSET_RANGE = -500..500
        val STEERING_SCALE_RANGE = 1..20000
        val INDICATOR_TIMEOUT_RANGE = 100..2000
        val RPM_DIVISOR_RANGE = 1..20
        val TANK_CAPACITY_RANGE = 20..100
        val DTE_DIVISOR_RANGE = 100..500
    }
}

