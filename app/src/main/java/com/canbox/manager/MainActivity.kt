package com.canbox.manager

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.canbox.manager.data.usb.UsbSerialService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.canbox.manager.data.github.GitHubRepository
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.util.isNewerVersion
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.ui.navigation.AppNavHost
import com.canbox.manager.ui.navigation.TopNavigationBar
import com.canbox.manager.ui.theme.CANBoxManagerTheme
import com.canbox.manager.ui.theme.StatusConnected
import com.canbox.manager.ui.theme.StatusDisconnected
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {

    private val repository: CanBoxRepository by inject()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled per-screen where needed */ }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connectAsync()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> repository.disconnect()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start foreground service so Android won't kill the process in background
        startUsbService(connected = false)

        // Request storage permission upfront (API <= 29 only — Android 10 and below)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(perm))
            }
        }

        // Update service notification when USB connection state changes
        lifecycleScope.launch {
            repository.connectionState.collect { state ->
                when (state) {
                    is UsbConnectionState.Connected ->
                        startUsbService(connected = true, deviceName = state.deviceName)
                    else ->
                        startUsbService(connected = false)
                }
            }
        }

        // Enable full screen immersive mode
        hideSystemBars()

        setContent {
            CANBoxManagerTheme {
                MainScreen(repository)
            }
        }
    }

    private fun startUsbService(connected: Boolean, deviceName: String? = null) {
        val intent = Intent(this, UsbSerialService::class.java).apply {
            putExtra(UsbSerialService.EXTRA_CONNECTED, connected)
            putExtra(UsbSerialService.EXTRA_DEVICE_NAME, deviceName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onResume() {
        super.onResume()
        // Register USB receiver for attach/detach while app is open
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        // Try to connect on resume (async to not block UI)
        connectAsync()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(usbReceiver)
    }

    private fun connectAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.connect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.release()
        stopService(Intent(this, UsbSerialService::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: CanBoxRepository) {
    val navController = rememberNavController()
    val connectionState by repository.connectionState.collectAsState()
    var showAboutDialog by remember { mutableStateOf(false) }
    var appUpdateVersion by remember { mutableStateOf<String?>(null) }
    val gitHubRepository: GitHubRepository = koinInject()

    // Check for app updates on startup
    var appCheckDone by remember { mutableStateOf(false) }
    var firmwareCheckDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        gitHubRepository.checkAppUpdate(BuildConfig.VERSION_NAME)
            .onSuccess { updateVersion ->
                appUpdateVersion = updateVersion
                if (updateVersion != null) {
                    showAboutDialog = true
                }
            }
        appCheckDone = true
    }

    // Check for ESP firmware updates when connected (only once)
    LaunchedEffect(connectionState.isConnected) {
        if (connectionState.isConnected && appCheckDone && !firmwareCheckDone && !showAboutDialog) {
            firmwareCheckDone = true
            // Get current firmware version
            repository.getSysInfo().onSuccess { firmwareInfo ->
                val currentVersion = firmwareInfo.version.removePrefix("v")
                // Get latest release
                gitHubRepository.getReleases().onSuccess { releases ->
                    val latest = releases.firstOrNull { !it.prerelease }
                    if (latest != null) {
                        val latestVersion = latest.tagName.removePrefix("v").removePrefix("V")
                        if (isNewerVersion(latestVersion, currentVersion)) {
                            navController.navigate("update") {
                                popUpTo("live") { inclusive = false }
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .displayCutoutPadding(),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Status bar
                StatusBar(
                    connectionState = connectionState,
                    onTitleClick = { showAboutDialog = true }
                )
                // Navigation
                TopNavigationBar(
                    navController = navController,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            AppNavHost(
                navController = navController,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showAboutDialog) {
        AboutOverlay(
            onDismiss = { showAboutDialog = false },
            updateAvailable = appUpdateVersion
        )
    }
}

@Composable
private fun StatusBar(
    connectionState: UsbConnectionState,
    onTitleClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App title (clickable for About)
            Row(
                modifier = Modifier.clickable { onTitleClick() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "CANBox",
                    style = MaterialTheme.typography.titleSmall
                )
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "About",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Connection status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (connectionState) {
                    is UsbConnectionState.Connected -> {
                        Icon(
                            imageVector = Icons.Filled.Usb,
                            contentDescription = null,
                            tint = StatusConnected,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = connectionState.deviceName.take(15),
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusConnected,
                            maxLines = 1
                        )
                    }
                    is UsbConnectionState.Connecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UsbConnectionState.Error -> {
                        Icon(
                            imageVector = Icons.Filled.UsbOff,
                            contentDescription = null,
                            tint = StatusDisconnected,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusDisconnected
                        )
                    }
                    is UsbConnectionState.Disconnected -> {
                        Icon(
                            imageVector = Icons.Filled.UsbOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "No USB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutOverlay(onDismiss: () -> Unit, updateAvailable: String?) {
    val context = LocalContext.current
    val isChecking = false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .clickable(enabled = false, onClick = {}),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "CANBox Manager",
                    style = MaterialTheme.typography.titleLarge
                )
                Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text("by aerodomigue", color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Update status
                when {
                    isChecking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Checking updates...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    updateAvailable != null -> {
                        Text(
                            "Update available: $updateAvailable",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> {
                        Text(
                            "Up to date",
                            color = StatusConnected,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aerodomigue/esp32-canbox-manager"))
                        context.startActivity(intent)
                    }) {
                        Text("GitHub")
                    }
                    if (updateAvailable != null) {
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aerodomigue/esp32-canbox-manager/releases/latest"))
                            context.startActivity(intent)
                        }) {
                            Text("Download")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

