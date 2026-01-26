package com.canbox.manager.ui.screens.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.canbox.manager.domain.model.CalibrationConfig
import org.koin.androidx.compose.koinViewModel

@Composable
fun CalibrationScreen(
    viewModel: CalibrationViewModel = koinViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(connectionState.isConnected) {
        if (connectionState.isConnected) {
            viewModel.loadConfig()
        }
    }

    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Messages
        uiState.error?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(error, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearMessages() }) {
                        Icon(Icons.Filled.Close, "Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        uiState.successMessage?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(message, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearMessages() }) {
                        Icon(Icons.Filled.Close, "Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (uiState.isLoading || uiState.isSaving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }

        // Settings content
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left column: Steering
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Steering",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(16.dp))

                    CalibrationSlider(
                        label = "Steering Offset",
                        value = uiState.config.steeringOffset,
                        range = CalibrationConfig.STEERING_OFFSET_RANGE,
                        onValueChange = { viewModel.updateSteeringOffset(it) }
                    )

                    CalibrationSlider(
                        label = "Steering Scale (x0.01)",
                        value = uiState.config.steeringScale,
                        range = CalibrationConfig.STEERING_SCALE_RANGE,
                        onValueChange = { viewModel.updateSteeringScale(it) }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Steering Invert")
                        Switch(
                            checked = uiState.config.steeringInvert,
                            onCheckedChange = { viewModel.updateSteeringInvert(it) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    CalibrationSlider(
                        label = "Indicator Timeout (ms)",
                        value = uiState.config.indicatorTimeout,
                        range = CalibrationConfig.INDICATOR_TIMEOUT_RANGE,
                        onValueChange = { viewModel.updateIndicatorTimeout(it) }
                    )
                }
            }

            // Right column: Engine & Fuel
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Engine & Fuel",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(16.dp))

                    CalibrationSlider(
                        label = "RPM Divisor",
                        value = uiState.config.rpmDivisor,
                        range = CalibrationConfig.RPM_DIVISOR_RANGE,
                        onValueChange = { viewModel.updateRpmDivisor(it) }
                    )

                    CalibrationSlider(
                        label = "Tank Capacity (L)",
                        value = uiState.config.tankCapacity,
                        range = CalibrationConfig.TANK_CAPACITY_RANGE,
                        onValueChange = { viewModel.updateTankCapacity(it) }
                    )

                    CalibrationSlider(
                        label = "DTE Divisor (x100)",
                        value = uiState.config.dteDivisor,
                        range = CalibrationConfig.DTE_DIVISOR_RANGE,
                        onValueChange = { viewModel.updateDteDivisor(it) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
        ) {
            OutlinedButton(
                onClick = { showResetDialog = true },
                enabled = !uiState.isSaving
            ) {
                Icon(Icons.Filled.RestartAlt, null)
                Spacer(Modifier.width(8.dp))
                Text("Reset")
            }

            Button(
                onClick = { viewModel.saveToNvs() },
                enabled = uiState.hasChanges && !uiState.isSaving
            ) {
                Icon(Icons.Filled.Save, null)
                Spacer(Modifier.width(8.dp))
                Text("Save to NVS")
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset to Defaults") },
            text = { Text("This will reset all calibration values to factory defaults. Continue?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetToDefaults()
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                range.first.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                range.last.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
