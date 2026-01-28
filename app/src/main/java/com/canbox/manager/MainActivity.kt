package com.canbox.manager

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.canbox.manager.data.repository.CanBoxRepository
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.ui.navigation.AppNavHost
import com.canbox.manager.ui.navigation.TopNavigationBar
import com.canbox.manager.ui.theme.CANBoxManagerTheme
import com.canbox.manager.ui.theme.StatusConnected
import com.canbox.manager.ui.theme.StatusDisconnected
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val repository: CanBoxRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable full screen immersive mode
        hideSystemBars()

        // Handle USB device attached intent
        handleIntent(intent)

        setContent {
            CANBoxManagerTheme {
                MainScreen(repository)
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // Automatically connect when USB device is attached
            repository.connect()
        }
    }

    override fun onResume() {
        super.onResume()
        // Try to connect on resume
        repository.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.disconnect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: CanBoxRepository) {
    val navController = rememberNavController()
    val connectionState by repository.connectionState.collectAsState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Status bar
                StatusBar(connectionState)
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
}

@Composable
private fun StatusBar(connectionState: UsbConnectionState) {
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
            // App title
            Text(
                text = "CANBox",
                style = MaterialTheme.typography.titleSmall
            )

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
