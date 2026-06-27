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

    @Volatile
    private var isConnecting = false

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
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.d(TAG, "USB permission granted")
                        device?.let { connectToDevice(it) }
                    } else {
                        Log.w(TAG, "USB permission denied")
                        isConnecting = false
                        _connectionState.value = UsbConnectionState.Error("Permission denied")
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
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun findDevices(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    @Synchronized
    fun connect(): Boolean {
        // Already connected or connecting
        if (_connectionState.value.isConnected || isConnecting) {
            Log.d(TAG, "Already connected or connecting, skipping")
            return _connectionState.value.isConnected
        }

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
            isConnecting = true
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

    @Synchronized
    private fun connectToDevice(device: UsbDevice): Boolean {
        // Already connected
        if (_connectionState.value.isConnected) {
            Log.d(TAG, "Already connected, skipping connectToDevice")
            isConnecting = false
            return true
        }

        isConnecting = true
        _connectionState.value = UsbConnectionState.Connecting

        try {
            // Close any existing connection first
            ioManager?.stop()
            ioManager = null
            try { serialPort?.close() } catch (e: Exception) { }
            serialPort = null

            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.find { it.device == device } ?: run {
                _connectionState.value = UsbConnectionState.Error("Driver not found")
                isConnecting = false
                return false
            }

            val connection = usbManager.openDevice(device) ?: run {
                _connectionState.value = UsbConnectionState.Error("Could not open device")
                isConnecting = false
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
            isConnecting = false
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = UsbConnectionState.Error(e.message ?: "Connection failed")
            isConnecting = false
            disconnect()
            return false
        }
    }

    @Synchronized
    fun disconnect() {
        isConnecting = false
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

                val data = "$command\n".toByteArray()
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

    @Synchronized
    override fun onNewData(data: ByteArray) {
        val text = String(data)
        responseBuffer.append(text)

        // Emit each incoming chunk immediately for streaming consumers (CAN log frames).
        _receivedData.tryEmit(text)

        val response = responseBuffer.toString()

        // Only inspect complete lines (terminated by \n) to avoid false positives on partial data.
        // split('\n').dropLast(1) gives all newline-terminated segments.
        val completeLines = response.split('\n').dropLast(1).map { it.trimEnd('\r').trim() }

        val hasOkResponse = completeLines.any { it.startsWith("OK") }
        val hasErrorResponse = completeLines.any { it.startsWith("ERROR") }
        // Prompt arrives without trailing \n — keep raw-string check
        val hasPrompt = response.contains("\r\n> ") || response.contains("\n> ")
        val isDelimiterLine = completeLines.lastOrNull { it.isNotBlank() }
            ?.let { line -> line.length >= 10 && line.all { it == '=' } } == true

        // "OTA aborted" has no OK/ERROR prefix: do NOT detect it here so that
        // sendCommand("OTA ABORT", 1000) always times out after 1s — that IS the required drain.
        val isComplete = hasOkResponse || hasErrorResponse || hasPrompt ||
            (isDelimiterLine && completeLines.size >= 2)

        if (isComplete) {
            val completeResponse = responseBuffer.toString().trim()
            responseBuffer.clear()

            Log.d(TAG, "Complete response: ${completeResponse.take(150)}")

            pendingResponseChannel?.trySend(completeResponse)
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
