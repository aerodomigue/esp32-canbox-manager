package com.canbox.manager.domain.model

data class FirmwareInfo(
    val version: String = "Unknown",
    val buildDate: String = "",
    val chipModel: String = "",
    val freeHeap: Int = 0,
    val uptime: Long = 0
)

data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val assets: List<ReleaseAsset>,
    val prerelease: Boolean = false
) {
    val version: String
        get() = tagName.removePrefix("v")

    val firmwareAsset: ReleaseAsset?
        get() = assets.find { it.name.endsWith(".ota") }
            ?: assets.find { it.name == "firmware.bin" }
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long
)

enum class UpdateMethod {
    ESPTOOL,    // Fast: SYS BOOTLOADER + esptool protocol
    OTA_SERIAL  // Fallback: OTA START/DATA/END base64
}

data class UpdateProgress(
    val state: UpdateState,
    val progress: Float = 0f,     // 0.0 to 1.0
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val method: UpdateMethod = UpdateMethod.ESPTOOL,
    val message: String = ""
)

enum class UpdateState {
    IDLE,
    DOWNLOADING,
    PREPARING,
    FLASHING,
    VERIFYING,
    REBOOTING,
    SUCCESS,
    ERROR
}
