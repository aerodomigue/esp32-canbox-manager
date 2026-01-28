package com.canbox.manager.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.concurrent.Executors

class UsbSerialManager(
    private val context: Context
) : SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "UsbSerialManager"
        private const val ACTION_USB_PERMISSION = "com.canbox.manager.USB_PERMISSION"
        private const val BAUD_RATE = 115200
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 1000
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val _receivedData = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 100)
    val receivedData: SharedFlow<String> = _receivedData.asSharedFlow()

    private val responseBuffer = StringBuilder()
    private var pendingResponseChannel: Channel<String>? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let { connectToDevice(it) }
                        } else {
                            Log.w(TAG, "USB permission denied")
                            _connectionState.value = UsbConnectionState.Error("Permission denied")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "USB device detached")
                    disconnect()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun findDevices(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    fun connect(): Boolean {
        val drivers = findDevices()
        if (drivers.isEmpty()) {
            Log.d(TAG, "No USB serial devices found")
            _connectionState.value = UsbConnectionState.Disconnected
            return false
        }

        val driver = drivers[0]
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission")
            _connectionState.value = UsbConnectionState.Connecting
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                flags
            )
            usbManager.requestPermission(device, permissionIntent)
            return false
        }

        return connectToDevice(device)
    }

    private fun connectToDevice(device: UsbDevice): Boolean {
        _connectionState.value = UsbConnectionState.Connecting

        try {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.find { it.device == device } ?: run {
                _connectionState.value = UsbConnectionState.Error("Driver not found")
                return false
            }

            val connection = usbManager.openDevice(device) ?: run {
                _connectionState.value = UsbConnectionState.Error("Could not open device")
                return false
            }

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(
                BAUD_RATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            port.dtr = true
            port.rts = true

            serialPort = port

            // Start IO manager for async reading
            ioManager = SerialInputOutputManager(port, this).also {
                executor.submit(it)
            }

            _connectionState.value = UsbConnectionState.Connected(
                deviceName = device.productName ?: "USB Device",
                vendorId = device.vendorId,
                productId = device.productId
            )

            Log.d(TAG, "Connected to ${device.productName}")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = UsbConnectionState.Error(e.message ?: "Connection failed")
            disconnect()
            return false
        }
    }

    fun disconnect() {
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null

        try {
            serialPort?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing port", e)
        }
        serialPort = null

        _connectionState.value = UsbConnectionState.Disconnected
        Log.d(TAG, "Disconnected")
    }

    suspend fun sendCommand(command: String, timeoutMs: Long = 2000L): Result<String> {
        val port = serialPort ?: return Result.failure(IOException("Not connected"))

        return withContext(Dispatchers.IO) {
            try {
                // Clear any pending data
                responseBuffer.clear()
                pendingResponseChannel = Channel(1)

                // Send command with newline
                val data = "$command\r\n".toByteArray()
                port.write(data, WRITE_TIMEOUT_MS)

                // Wait for response
                val response = withTimeoutOrNull(timeoutMs) {
                    pendingResponseChannel?.receive()
                }

                if (response == null) {
                    val bufferContent = responseBuffer.toString()
                    Log.e(TAG, "Command timeout: $command (buffer: $bufferContent)")
                    pendingResponseChannel = null
                    return@withContext Result.failure(IOException("Command timeout"))
                }

                pendingResponseChannel = null
                Result.success(response)

            } catch (e: Exception) {
                Log.e(TAG, "Send command failed: $command", e)
                pendingResponseChannel = null
                Result.failure(e)
            }
        }
    }

    private var writeCounter = 0

    fun write(data: ByteArray): Boolean {
        return try {
            serialPort?.write(data, WRITE_TIMEOUT_MS)
            writeCounter++
            if (writeCounter % 100 == 0) {
                Log.d(TAG, "Write #$writeCounter: ${data.size} bytes")
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Write #$writeCounter failed: ${e.message}")
            false
        }
    }

    fun resetWriteCounter() {
        writeCounter = 0
    }

    /**
     * Send raw binary data (for OTA binary mode) and wait for JSON response
     */
    suspend fun sendBinaryData(data: ByteArray, timeoutMs: Long = 10000L): Result<String> {
        val port = serialPort ?: return Result.failure(IOException("Not connected"))

        return withContext(Dispatchers.IO) {
            try {
                // Clear any pending data
                responseBuffer.clear()
                pendingResponseChannel = Channel(1)

                // Send raw binary data directly
                port.write(data, WRITE_TIMEOUT_MS)
                Log.d(TAG, "Sent binary: ${data.size} bytes (timeout=${timeoutMs}ms)")

                // Wait for JSON response
                val response = withTimeoutOrNull(timeoutMs) {
                    pendingResponseChannel?.receive()
                }

                if (response == null) {
                    val bufferContent = responseBuffer.toString()
                    Log.e(TAG, "Binary data timeout! Buffer content (${bufferContent.length} chars): $bufferContent")
                    pendingResponseChannel = null
                    return@withContext Result.failure(IOException("Command timeout"))
                }

                Log.d(TAG, "Received response (${response.length} chars)")
                pendingResponseChannel = null
                Result.success(response)

            } catch (e: Exception) {
                Log.e(TAG, "Send binary data failed", e)
                pendingResponseChannel = null
                Result.failure(e)
            }
        }
    }

    /**
     * Send binary chunk and wait for JSON ACK response (for OTA v2 chunk-ACK protocol).
     * Used when ESP32 sends ACK after each chunk.
     */
    suspend fun sendBinaryAndWaitResponse(data: ByteArray, timeoutMs: Long = 5000L): Result<String> {
        val port = serialPort ?: return Result.failure(IOException("Not connected"))

        return withContext(Dispatchers.IO) {
            try {
                // Clear buffer and set up channel for response (synchronized with onNewData)
                synchronized(this@UsbSerialManager) {
                    responseBuffer.clear()
                    pendingResponseChannel = Channel(1)
                }

                // Send raw binary chunk
                port.write(data, WRITE_TIMEOUT_MS)
                Log.d(TAG, "Chunk sent: ${data.size} bytes")

                // Wait for JSON ACK from ESP32
                val response = withTimeoutOrNull(timeoutMs) {
                    pendingResponseChannel?.receive()
                }

                synchronized(this@UsbSerialManager) {
                    pendingResponseChannel = null
                }

                if (response == null) {
                    val bufferContent = synchronized(this@UsbSerialManager) { responseBuffer.toString() }
                    Log.e(TAG, "Chunk ACK timeout! Buffer (${bufferContent.length} chars): $bufferContent")
                    return@withContext Result.failure(IOException("Chunk ACK timeout"))
                }

                Log.d(TAG, "Chunk ACK received: ${response.take(100)}")
                Result.success(response)

            } catch (e: Exception) {
                Log.e(TAG, "Send chunk failed: ${e.message}")
                synchronized(this@UsbSerialManager) {
                    pendingResponseChannel = null
                }
                Result.failure(e)
            }
        }
    }

    /**
     * Non-blocking read of any available data in buffer.
     * Returns null if no complete response available.
     */
    @Synchronized
    fun readAvailable(): String? {
        val content = responseBuffer.toString()
        if (content.isBlank()) return null

        // Check if we have a complete JSON response
        val jsonStart = content.indexOf('{')
        val jsonEnd = content.lastIndexOf('}')
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            try {
                val json = content.substring(jsonStart, jsonEnd + 1)
                // Clear the consumed part
                responseBuffer.delete(0, minOf(jsonEnd + 1, responseBuffer.length))
                return json
            } catch (e: Exception) {
                Log.e(TAG, "readAvailable error: ${e.message}")
                return null
            }
        }

        return null
    }

    /**
     * Blocking wait for a response (used after OTA binary transfer completes)
     */
    suspend fun waitForResponse(timeoutMs: Long = 10000L): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // Set up channel for response
                pendingResponseChannel = Channel(1)

                Log.d(TAG, "Waiting for response (timeout=${timeoutMs}ms), buffer=${responseBuffer.length} chars")

                // Wait for response
                val response = withTimeoutOrNull(timeoutMs) {
                    pendingResponseChannel?.receive()
                }

                pendingResponseChannel = null

                if (response == null) {
                    val bufferContent = responseBuffer.toString()
                    Log.e(TAG, "Wait timeout! Buffer (${bufferContent.length} chars): $bufferContent")
                    Result.failure(IOException("Response timeout"))
                } else {
                    Log.d(TAG, "Got response: ${response.take(200)}")
                    Result.success(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Wait for response failed", e)
                pendingResponseChannel = null
                Result.failure(e)
            }
        }
    }

    @Synchronized
    override fun onNewData(data: ByteArray) {
        val text = String(data)
        // Log raw data from ESP32
        if (text.contains("{") || text.contains("status")) {
            Log.d(TAG, "ESP32 raw: ${text.take(200)}")
        }
        responseBuffer.append(text)

        // Check for complete response
        val response = responseBuffer.toString()
        val lines = response.lines()

        // Response is complete when:
        // - Contains OK or ERROR (with newline to avoid partial matches)
        // - Ends with a delimiter line (only = characters, at least 10)
        // - Has a prompt "> "
        // - Contains a complete JSON response (for OTA v2)
        val lastLine = lines.lastOrNull { it.isNotBlank() } ?: ""
        val isDelimiterLine = lastLine.length >= 10 && lastLine.all { it == '=' }

        // For legacy OTA responses, look for OK at start of line or ERROR
        val hasOkResponse = response.contains("\nOK") || response.startsWith("OK") ||
            response.contains("\r\nOK")
        val hasErrorResponse = response.contains("ERROR")
        val hasPrompt = response.contains("\r\n> ") || response.contains("\n> ")

        // For OTA v2 JSON responses: {"status":"ok",...} or {"status":"error",...}
        val hasJsonResponse = (response.contains("{\"status\":\"ok\"") || response.contains("{\"status\":\"error\"")) &&
            response.contains("}") && response.indexOf('}') > response.indexOf('{')

        val isComplete = hasOkResponse || hasErrorResponse || hasPrompt || hasJsonResponse ||
            (isDelimiterLine && lines.size >= 2)

        if (isComplete) {
            val completeResponse = responseBuffer.toString().trim()
            responseBuffer.clear()

            Log.d(TAG, "Complete response: ${completeResponse.take(150)}")

            // Send to channel if waiting for response
            pendingResponseChannel?.trySend(completeResponse)

            // Also emit to flow for streaming data
            _receivedData.tryEmit(completeResponse)
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "IO error", e)
        _connectionState.value = UsbConnectionState.Error(e.message ?: "IO error")
        disconnect()
    }

    fun release() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        executor.shutdown()
    }
}

sealed class UsbConnectionState {
    data object Disconnected : UsbConnectionState()
    data object Connecting : UsbConnectionState()
    data class Connected(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int
    ) : UsbConnectionState()
    data class Error(val message: String) : UsbConnectionState()

    val isConnected: Boolean
        get() = this is Connected
}
