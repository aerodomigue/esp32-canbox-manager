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
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // A flash drive on the same hub must not be mistaken for the serial device.
    @Volatile private var selectedDeviceId: Int? = null
    private var connectionSequence = 0L
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isConnecting = false

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    // The logger must not lose USB chunks because a UI collector is slow.
    // Command responses are parsed synchronously below; this channel is the
    // independent stream consumed by the long-lived repository logger.
    private val receivedDataChannel = Channel<String>(Channel.UNLIMITED)
    val receivedData: Flow<String> = receivedDataChannel.receiveAsFlow()

    // The serial protocol has one response buffer, so only one command may be
    // in flight at a time.
    private val commandMutex = Mutex()

    private val responseBuffer = CommandResponseBuffer()
    private val textDecoder = Utf8StreamDecoder()
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
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    }
                    if (isSelectedDevice(device?.deviceId)) {
                        Log.d(TAG, "Serial USB device detached")
                        disconnect()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached")
                    connect()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun findDevices(): List<UsbSerialDriver> {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    private fun isSelectedDevice(deviceId: Int?): Boolean =
        deviceId != null && deviceId == selectedDeviceId

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
        selectedDeviceId = device.deviceId

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
        selectedDeviceId = device.deviceId
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
                productId = device.productId,
                sessionId = ++connectionSequence
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
        selectedDeviceId = null
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null

        try {
            serialPort?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing port", e)
        }
        serialPort = null

        textDecoder.reset()
        responseBuffer.reset()
        _connectionState.value = UsbConnectionState.Disconnected
        Log.d(TAG, "Disconnected")
    }

    suspend fun sendCommand(command: String, timeoutMs: Long = 2000L): Result<String> =
        commandMutex.withLock {
            val port = serialPort ?: return@withLock Result.failure(IOException("Not connected"))

            withContext(Dispatchers.IO) {
                val responseChannel = Channel<String>(1)
                var timedOut = false
                try {
                    synchronized(this@UsbSerialManager) {
                        responseBuffer.begin(command)
                        pendingResponseChannel = responseChannel
                    }

                    port.write("$command\n".toByteArray(), WRITE_TIMEOUT_MS)
                    val response = withTimeoutOrNull(timeoutMs) {
                        responseChannel.receive()
                    }

                    if (response == null) {
                        timedOut = true
                        val bufferContent = synchronized(this@UsbSerialManager) {
                            responseBuffer.partialResponse()
                        }
                        Log.e(TAG, "Command timeout: $command (buffer: $bufferContent)")
                        Result.failure(IOException("Command timeout"))
                    } else {
                        Result.success(response)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Send command failed: $command", e)
                    Result.failure(e)
                } finally {
                    synchronized(this@UsbSerialManager) {
                        if (pendingResponseChannel === responseChannel) {
                            pendingResponseChannel = null
                            responseBuffer.end()
                        }
                    }
                    responseChannel.close()
                    if (timedOut) {
                        // Drain late bytes before allowing the next command to
                        // install a response channel; otherwise a delayed OK
                        // can be attributed to the wrong command.
                        delay(READ_TIMEOUT_MS.toLong())
                    }
                }
            }
        }

    @Synchronized
    override fun onNewData(data: ByteArray) {
        val text = textDecoder.decode(data)

        // Emit each incoming chunk immediately for streaming consumers (CAN log frames).
        receivedDataChannel.trySend(text)

        // Keep framing even between commands; an ODB line may cross that boundary.
        val completeResponse = responseBuffer.append(text)
        if (completeResponse != null) {
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
        receivedDataChannel.close()
    }
}

sealed class UsbConnectionState {
    data object Disconnected : UsbConnectionState()
    data object Connecting : UsbConnectionState()
    data class Connected(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val sessionId: Long = 0
    ) : UsbConnectionState()
    data class Error(val message: String) : UsbConnectionState()

    val isConnected: Boolean
        get() = this is Connected
}
