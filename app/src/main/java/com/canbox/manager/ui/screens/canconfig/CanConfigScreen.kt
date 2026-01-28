package com.canbox.manager.ui.screens.canconfig

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.canbox.manager.data.github.GitHubConfigFile
import com.canbox.manager.domain.model.CanConfigFile
import com.canbox.manager.domain.model.VehicleMode
import com.canbox.manager.ui.theme.StatusConnected
import com.canbox.manager.ui.theme.StatusWarning
import org.koin.androidx.compose.koinViewModel

@Composable
fun CanConfigScreen(
    viewModel: CanConfigViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    // File picker for config import
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importFromFile(it, context)
        }
    }

    LaunchedEffect(connectionState.isConnected) {
        if (connectionState.isConnected) {
            viewModel.refresh()
        }
    }

    // Load GitHub configs on first composition
    LaunchedEffect(Unit) {
        viewModel.loadGithubConfigs()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header with status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                val modeText = if (uiState.activeConfig != null) {
                    "${uiState.mode.name} (${uiState.activeConfig})"
                } else {
                    uiState.mode.name
                }
                Text(
                    text = modeText,
                    style = MaterialTheme.typography.titleMedium,
                    color = when (uiState.mode) {
                        VehicleMode.REAL -> StatusConnected
                        VehicleMode.MOCK -> StatusWarning
                        VehicleMode.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Error message
        uiState.error?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Loading indicator
        if (uiState.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Content in a scrollable column
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Device configs section
            item {
                Text(
                    text = "On Device",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (uiState.deviceFiles.isEmpty() && !uiState.isLoading) {
                item {
                    Text(
                        text = "No configuration files",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(uiState.deviceFiles) { file ->
                    ConfigFileItem(
                        file = file,
                        onLoad = { viewModel.loadConfig(file.filename) },
                        onDelete = { viewModel.deleteConfig(file.filename) }
                    )
                }
            }

            // GitHub configs section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Available on GitHub",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.isLoadingGithub) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (uiState.githubFiles.isEmpty() && !uiState.isLoadingGithub) {
                item {
                    Text(
                        text = "No files found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(uiState.githubFiles) { file ->
                    val alreadyOnDevice = uiState.deviceFiles.any { it.filename == file.name }
                    GitHubFileItem(
                        file = file,
                        alreadyOnDevice = alreadyOnDevice,
                        onDownload = { viewModel.downloadAndUpload(file) }
                    )
                }
            }

            // Import from file button
            item {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import from file")
                }
            }
        }
    }
}

@Composable
private fun ConfigFileItem(
    file: CanConfigFile,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (file.isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (file.isActive) Icons.Filled.CheckCircle else Icons.Filled.Description,
                    contentDescription = null,
                    tint = if (file.isActive) StatusConnected else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column {
                    Text(
                        text = file.filename,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${file.size} bytes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row {
                if (!file.isActive) {
                    TextButton(onClick = onLoad) {
                        Text("Load")
                    }
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Configuration") },
            text = { Text("Are you sure you want to delete ${file.filename}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun GitHubFileItem(
    file: GitHubConfigFile,
    alreadyOnDevice: Boolean,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${file.size} bytes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (alreadyOnDevice) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Already on device",
                    tint = StatusConnected
                )
            } else {
                TextButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }
    }
}
