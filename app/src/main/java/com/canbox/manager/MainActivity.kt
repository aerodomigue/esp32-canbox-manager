package com.canbox.manager

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        enableEdgeToEdge()

        // Handle USB device attached intent
        handleIntent(intent)

        setContent {
            CANBoxManagerTheme {
                MainScreen(repository)
            }
        }
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
        topBar = {
            Column {
                // Status bar
                StatusBar(connectionState)
                // Navigation
                TopNavigationBar(navController)
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App title
            Text(
                text = "CANBox Manager",
                style = MaterialTheme.typography.titleMedium
            )

            // Connection status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (connectionState) {
                    is UsbConnectionState.Connected -> {
                        Icon(
                            imageVector = Icons.Filled.Usb,
                            contentDescription = null,
                            tint = StatusConnected,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = connectionState.deviceName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusConnected
                        )
                    }
                    is UsbConnectionState.Connecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Connecting...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UsbConnectionState.Error -> {
                        Icon(
                            imageVector = Icons.Filled.UsbOff,
                            contentDescription = null,
                            tint = StatusDisconnected,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = StatusDisconnected
                        )
                    }
                    is UsbConnectionState.Disconnected -> {
                        Icon(
                            imageVector = Icons.Filled.UsbOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Disconnected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
