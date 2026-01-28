package com.canbox.manager.data.usb

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * OTA v2 Protocol - JSON response models
 *
 * Commands:
 * - OTA START <size> [md5]  → Mode base64
 * - OTA BIN <size> [md5]    → Mode binaire (recommandé)
 * - OTA DATA <base64>       → Envoi base64 (mode base64 uniquement)
 * - [raw bytes]             → Envoi binaire (mode binaire, pas de commande)
 * - OTA END                 → Finalisation
 * - OTA ABORT               → Annulation
 * - OTA STATUS              → État actuel
 */

sealed class OtaResponse {
    abstract val status: String
    abstract val cmd: String

    val isSuccess: Boolean get() = status == "ok"
    val isError: Boolean get() = status == "error"
}

/**
 * Response to OTA START / OTA BIN
 */
data class OtaStartResponse(
    override val status: String,
    override val cmd: String,  // "OTA2_START"
    val total: Long,
    val md5: Boolean = false,
    @SerializedName("chunk_max")
    val chunkMax: Int = 512,
    @SerializedName("timeout_ms")
    val timeoutMs: Long = 30000,
    val mode: String = "base64"  // "base64" or "binary"
) : OtaResponse()

/**
 * Response to OTA DATA (base64 mode) or raw bytes (binary mode)
 * Note: In binary mode, response is sent every 4KB
 */
data class OtaDataResponse(
    override val status: String,
    override val cmd: String,  // "OTA2_DATA" or "OTA2_BIN"
    val received: Long = 0,
    val total: Long = 0,
    val percent: Int = 0
) : OtaResponse()

/**
 * Response to OTA STATUS
 */
data class OtaStatusResponse(
    override val status: String,
    override val cmd: String,  // "OTA2_STATUS"
    @SerializedName("in_progress")
    val inProgress: Boolean = false,
    val received: Long = 0,
    val total: Long = 0,
    val percent: Int = 0,
    @SerializedName("free_space")
    val freeSpace: Long = 0,
    @SerializedName("current_size")
    val currentSize: Long = 0
) : OtaResponse()

/**
 * Response to OTA END
 */
data class OtaEndResponse(
    override val status: String,
    override val cmd: String,  // "OTA2_END"
    @SerializedName("md5_verified")
    val md5Verified: Boolean = true,
    @SerializedName("reboot_delay")
    val rebootDelay: Int = 2000  // ms before ESP32 reboots
) : OtaResponse()

/**
 * Response to OTA ABORT
 */
data class OtaAbortResponse(
    override val status: String,
    override val cmd: String  // "OTA2_ABORT"
) : OtaResponse()

/**
 * Error response (any command)
 */
data class OtaErrorResponse(
    override val status: String,
    override val cmd: String,
    val code: Int,
    val message: String
) : OtaResponse()

/**
 * OTA v2 Error Codes
 */
object OtaErrorCodes {
    const val OK = 0                        // Succès
    const val NOT_IN_PROGRESS = 1           // Aucun OTA en cours
    const val ALREADY_IN_PROGRESS = 2       // OTA déjà en cours
    const val INVALID_MODE = 3              // Mode invalide (ex: OTA DATA en mode binaire)
    const val SIZE_TOO_LARGE = 4            // Taille trop grande pour la partition
    const val DECODE_FAILED = 5             // Échec décodage base64
    const val WRITE_FAILED = 6              // Échec écriture flash
    const val MD5_MISMATCH = 7              // MD5 incorrect
    const val INCOMPLETE = 8                // Transfert incomplet
    const val BEGIN_FAILED = 9              // Échec initialisation Update.begin()
    const val TIMEOUT = 10                  // Timeout (30s d'inactivité)

    fun getMessage(code: Int): String = when (code) {
        OK -> "OK"
        NOT_IN_PROGRESS -> "No OTA in progress"
        ALREADY_IN_PROGRESS -> "OTA already in progress"
        INVALID_MODE -> "Invalid mode (binary mode active, send raw bytes)"
        SIZE_TOO_LARGE -> "Size too large for partition"
        DECODE_FAILED -> "Base64 decode failed"
        WRITE_FAILED -> "Flash write failed"
        MD5_MISMATCH -> "MD5 checksum mismatch"
        INCOMPLETE -> "Transfer incomplete"
        BEGIN_FAILED -> "OTA initialization failed"
        TIMEOUT -> "Transfer timeout (30s)"
        else -> "Unknown error ($code)"
    }
}

object OtaResponseParser {
    private val gson = Gson()

    fun parseStartResponse(json: String): Result<OtaStartResponse> = parseResponse(json)
    fun parseDataResponse(json: String): Result<OtaDataResponse> = parseResponse(json)
    fun parseStatusResponse(json: String): Result<OtaStatusResponse> = parseResponse(json)
    fun parseEndResponse(json: String): Result<OtaEndResponse> = parseResponse(json)

    private inline fun <reified T : OtaResponse> parseResponse(json: String): Result<T> {
        return try {
            // Extract JSON from response (might have echo or other text before it)
            val jsonStart = json.indexOf('{')
            val jsonEnd = json.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1) {
                return Result.failure(Exception("No JSON found in response: $json"))
            }

            val jsonStr = json.substring(jsonStart, jsonEnd + 1)

            // First check if it's an error response
            if (jsonStr.contains("\"status\":\"error\"")) {
                val error = gson.fromJson(jsonStr, OtaErrorResponse::class.java)
                return Result.failure(OtaException(error.code, error.message))
            }

            val response = gson.fromJson(jsonStr, T::class.java)
            Result.success(response)
        } catch (e: OtaException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(Exception("Failed to parse OTA response: ${e.message}, json=$json"))
        }
    }
}

/**
 * Exception with OTA error code
 */
class OtaException(
    val code: Int,
    override val message: String
) : Exception("OTA error $code: $message") {

    val isRetryable: Boolean get() = when (code) {
        OtaErrorCodes.WRITE_FAILED -> true  // Can retry chunk
        OtaErrorCodes.TIMEOUT -> true       // Can restart
        else -> false
    }
}

/**
 * Escape sequence for aborting binary transfer
 * Send 5x ESC (0x1B) bytes to abort
 */
val OTA_ESCAPE_SEQUENCE = byteArrayOf(0x1B, 0x1B, 0x1B, 0x1B, 0x1B)
