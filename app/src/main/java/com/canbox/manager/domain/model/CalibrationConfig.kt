package com.canbox.manager.domain.model

data class CalibrationConfig(
    val steeringOffset: Int,
    val steeringScale: Int,
    val steeringInvert: Boolean,
    val indicatorTimeout: Int,
    val rpmDivisor: Int,
    val tankCapacity: Int,
    val dteDivisor: Int
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
