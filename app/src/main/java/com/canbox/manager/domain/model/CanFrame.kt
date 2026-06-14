package com.canbox.manager.domain.model

data class CanFrame(
    val timestamp: Long,
    val direction: FrameDirection,
    val canId: Int,
    val dlc: Int,
    val data: ByteArray,
    val source: FrameSource = FrameSource.VEHICLE
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CanFrame

        if (timestamp != other.timestamp) return false
        if (direction != other.direction) return false
        if (canId != other.canId) return false
        if (dlc != other.dlc) return false
        if (!data.contentEquals(other.data)) return false
        if (source != other.source) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + canId
        result = 31 * result + dlc
        result = 31 * result + data.contentHashCode()
        result = 31 * result + source.hashCode()
        return result
    }

    fun dataToHex(): String {
        return data.joinToString(" ") { "%02X".format(it) }
    }

    fun canIdHex(): String {
        return "0x%03X".format(canId)
    }
}

enum class FrameDirection {
    RX, TX
}

enum class FrameSource {
    VEHICLE,
    HEADUNIT
}

