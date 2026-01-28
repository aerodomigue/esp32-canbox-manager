package com.canbox.manager.ui.screens.debug

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
import com.canbox.manager.domain.model.CanFilter
import com.canbox.manager.domain.model.CanFrame
import com.canbox.manager.ui.theme.SurfaceVariant
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
            // Start/Stop button
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Stop, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }

            // Pause button
            IconButton(
                onClick = { viewModel.togglePause() },
                enabled = uiState.isLogging
            ) {
                Icon(
                    if (uiState.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (uiState.isPaused) "Resume" else "Pause"
                )
            }

            // Clear button
            IconButton(onClick = { viewModel.clearFrames() }) {
                Icon(Icons.Filled.Delete, "Clear")
            }

            // Export button
            IconButton(
                onClick = { viewModel.exportToFile(context) },
                enabled = uiState.frames.isNotEmpty()
            ) {
                Icon(Icons.Filled.Share, "Export")
            }

            Spacer(Modifier.weight(1f))

            // Stats
            Text(
                "Total: ${uiState.totalFrames}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        // Error message
        uiState.error?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(error, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Filled.Close, "Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Main content: Frames list + Filters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Frames list
            Card(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Time",
                            modifier = Modifier.width(100.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "ID",
                            modifier = Modifier.width(64.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "DLC",
                            modifier = Modifier.width(32.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            "Data",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    // Frames
                    val listState = rememberLazyListState()

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = uiState.frames,
                            key = { "${it.timestamp}-${it.canId}-${it.data.contentHashCode()}" }
                        ) { frame ->
                            CanFrameRow(frame)
                        }
                    }
                }
            }

            // Filters panel
            Card(
                modifier = Modifier
                    .width(180.dp)
                    .fillMaxHeight(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        "Filters",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    uiState.filters.forEach { filter ->
                        FilterCheckbox(
                            filter = filter,
                            onToggle = { viewModel.toggleFilter(filter.canId) }
                        )
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

@Composable
private fun FilterCheckbox(
    filter: CanFilter,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = filter.enabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(32.dp)
        )
        Column {
            Text(
                "0x%03X".format(filter.canId),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                filter.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
