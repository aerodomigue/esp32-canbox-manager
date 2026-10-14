package com.canbox.manager.ui.screens.debug

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import org.koin.androidx.compose.koinViewModel

@Composable
fun DebugScreen(
    viewModel: DebugViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onStoragePermissionResult(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        )
    }

    LaunchedEffect(uiState.storagePermissionRequired) {
        if (uiState.storagePermissionRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModel.storagePermissionOpened()
            try {
                storagePermission.launch(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ))
            } catch (_: ActivityNotFoundException) {
                try {
                    storagePermission.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: ActivityNotFoundException) {
                    viewModel.onStoragePermissionResult(false)
                } catch (_: SecurityException) {
                    viewModel.onStoragePermissionResult(false)
                }
            } catch (_: SecurityException) {
                viewModel.onStoragePermissionResult(false)
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
                onClick = { viewModel.saveToFile() },
                enabled = !uiState.isExporting &&
                    (uiState.totalFrames > 0 || uiState.recoverablePaths.isNotEmpty())
            ) {
                Icon(Icons.Filled.Save, "Sauvegarder et copier sur USB / SD")
            }

            IconButton(
                onClick = { viewModel.sendSavedLogByEmail(context) },
                enabled = uiState.savedPath != null
            ) {
                Icon(Icons.Filled.Email, "Send by email")
            }

            Spacer(Modifier.weight(1f))

            Text(
                "Lines: ${uiState.totalFrames}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        if (uiState.isExporting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Sauvegarde et export du log complet…", style = MaterialTheme.typography.bodySmall)
        }
        uiState.exportMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }

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

        uiState.savedPath?.let { path ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (uiState.recoverablePaths.contains(path)) {
                            "Recovered log: $path"
                        } else {
                            "Saved: $path"
                        },
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
                    Text("CAN / ODB", style = MaterialTheme.typography.labelMedium)
                }

                val listState = rememberLazyListState()

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(
                        items = uiState.logEntries
                    ) { entry ->
                        RawLogRow(entry.rawLine.trimEnd('\r', '\n'))
                    }
                }
            }
        }
    }
}

@Composable
private fun RawLogRow(rawLine: String) {
    Text(
        text = rawLine,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.tertiary
    )
}
