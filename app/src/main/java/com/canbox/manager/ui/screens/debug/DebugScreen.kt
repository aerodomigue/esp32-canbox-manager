package com.canbox.manager.ui.screens.debug

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.canbox.manager.domain.model.CanFrame
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DebugScreen(
    viewModel: DebugViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.saveToFile()
        } else {
            viewModel.setError("Storage permission denied — cannot save log file")
        }
    }

    fun requestSave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModel.saveToFile()
        } else {
            val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                viewModel.saveToFile()
            } else {
                writePermissionLauncher.launch(permission)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Control bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!uiState.isLogging) {
                Button(
                    onClick = { viewModel.startLogging() },
                    enabled = connectionState.isConnected
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Start")
                }
            } else {
                Button(
                    onClick = { viewModel.stopLogging() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.Stop, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }

            IconButton(
                onClick = { viewModel.togglePause() },
                enabled = uiState.isLogging
            ) {
                Icon(
                    if (uiState.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (uiState.isPaused) "Resume" else "Pause"
                )
            }

            IconButton(onClick = { viewModel.clearFrames() }) {
                Icon(Icons.Filled.Delete, "Clear")
            }

            IconButton(
                onClick = { requestSave() },
                enabled = uiState.totalFrames > 0
            ) {
                Icon(Icons.Filled.Save, "Save to file")
            }

            Spacer(Modifier.weight(1f))

            Text(
                "Total: ${uiState.totalFrames}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        // Messages
        uiState.error?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearMessages() }) {
                        Icon(Icons.Filled.Close, "Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        val saveNotifPath = uiState.usbSavedPath ?: uiState.savedPath
        val saveNotifLabel = if (uiState.usbSavedPath != null) "Saved to USB: " else "Saved: "
        saveNotifPath?.let { path ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$saveNotifLabel$path",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(onClick = { viewModel.clearMessages() }) {
                        Icon(Icons.Filled.Close, "Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Frames list — full width, no filter panel
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Time",  modifier = Modifier.width(100.dp), style = MaterialTheme.typography.labelMedium)
                    Text("ID",   modifier = Modifier.width(64.dp),  style = MaterialTheme.typography.labelMedium)
                    Text("DLC",  modifier = Modifier.width(32.dp),  style = MaterialTheme.typography.labelMedium)
                    Text("Data", style = MaterialTheme.typography.labelMedium)
                }

                val listState = rememberLazyListState()

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(
                        items = uiState.frames,
                        key = { "${it.timestamp}-${it.canId}-${it.data.contentHashCode()}" }
                    ) { frame ->
                        CanFrameRow(frame)
                    }
                }
            }
        }
    }
}

@Composable
private fun CanFrameRow(frame: CanFrame) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            timeFormat.format(Date(frame.timestamp)),
            modifier = Modifier.width(100.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            frame.canIdHex(),
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "[${frame.dlc}]",
            modifier = Modifier.width(32.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        Text(
            frame.dataToHex(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
