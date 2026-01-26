package com.canbox.manager.domain.model

data class CanConfigFile(
    val filename: String,
    val size: Int,
    val isActive: Boolean = false
)

data class CanConfigStatus(
    val activeConfig: String?,
    val mode: VehicleMode,
    val files: List<CanConfigFile>
)
