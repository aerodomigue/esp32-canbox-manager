package com.canbox.manager.ui.screens.calibration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.canbox.manager.domain.model.CalibrationConfig
import kotlin.math.roundToInt
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

        // Settings content - single column
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Steering section
            Text(
                "Steering",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            CalibrationItem(
                label = "Steering Offset",
                value = uiState.config.steeringOffset,
                range = CalibrationConfig.STEERING_OFFSET_RANGE,
                onValueChange = { viewModel.updateSteeringOffset(it) }
            )

            CalibrationItem(
                label = "Steering Scale",
                value = uiState.config.steeringScale,
                range = CalibrationConfig.STEERING_SCALE_RANGE,
                onValueChange = { viewModel.updateSteeringScale(it) },
                displayDivisor = 100
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Steering Invert", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = uiState.config.steeringInvert,
                    onCheckedChange = { viewModel.updateSteeringInvert(it) }
                )
            }

            CalibrationItem(
                label = "Indicator Timeout (ms)",
                value = uiState.config.indicatorTimeout,
                range = CalibrationConfig.INDICATOR_TIMEOUT_RANGE,
                onValueChange = { viewModel.updateIndicatorTimeout(it) }
            )

            Spacer(Modifier.height(8.dp))

            // Engine & Fuel section
            Text(
                "Engine & Fuel",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            CalibrationItem(
                label = "RPM Divisor",
                value = uiState.config.rpmDivisor,
                range = CalibrationConfig.RPM_DIVISOR_RANGE,
                onValueChange = { viewModel.updateRpmDivisor(it) }
            )

            CalibrationItem(
                label = "Tank Capacity (L)",
                value = uiState.config.tankCapacity,
                range = CalibrationConfig.TANK_CAPACITY_RANGE,
                onValueChange = { viewModel.updateTankCapacity(it) }
            )

            CalibrationItem(
                label = "DTE Divisor (x100)",
                value = uiState.config.dteDivisor,
                range = CalibrationConfig.DTE_DIVISOR_RANGE,
                onValueChange = { viewModel.updateDteDivisor(it) }
            )
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
private fun CalibrationItem(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    displayDivisor: Int = 1
) {
    val isDecimal = displayDivisor > 1

    fun formatDisplay(raw: Int): String =
        if (isDecimal) "%.2f".format(raw.toFloat() / displayDivisor) else raw.toString()

    fun formatRangeBound(raw: Int): String =
        if (isDecimal) "%.2f".format(raw.toFloat() / displayDivisor) else raw.toString()

    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember(value) { mutableStateOf(formatDisplay(value)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                // Clickable value for direct editing
                Surface(
                    modifier = Modifier.clickable { showEditDialog = true },
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = formatDisplay(value),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
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
                    formatRangeBound(range.first),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatRangeBound(range.last),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Edit dialog for precise input
    if (showEditDialog) {
        fun parseInput(text: String): Int? {
            val normalized = text.replace(',', '.')
            return if (isDecimal) {
                normalized.toFloatOrNull()?.let { (it * displayDivisor).roundToInt() }
            } else {
                normalized.toIntOrNull()
            }
        }

        val parsedValue = parseInput(editText)
        val isValid = parsedValue != null && parsedValue in range

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(label) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isDecimal) KeyboardType.Decimal else KeyboardType.Number
                        ),
                        singleLine = true,
                        isError = editText.isNotEmpty() && !isValid,
                        label = {
                            Text("Value (${formatRangeBound(range.first)} - ${formatRangeBound(range.last)})")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (editText.isNotEmpty() && !isValid) {
                        Text(
                            text = "Out of range: ${formatRangeBound(range.first)} – ${formatRangeBound(range.last)}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isValid) {
                            onValueChange(parsedValue!!)
                        }
                        showEditDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
