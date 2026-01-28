package com.canbox.manager.ui.screens.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.canbox.manager.domain.model.GitHubRelease
import com.canbox.manager.domain.model.UpdateState
import com.canbox.manager.ui.theme.StatusConnected
import com.canbox.manager.ui.theme.StatusWarning
import org.koin.androidx.compose.koinViewModel

@Composable
fun UpdateScreen(
    viewModel: UpdateViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    // File picker for local firmware
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.installFromFile(it, context)
        }
    }

    // Keep screen on during update
    val isUpdating = uiState.updateProgress.state != UpdateState.IDLE &&
            uiState.updateProgress.state != UpdateState.SUCCESS &&
            uiState.updateProgress.state != UpdateState.ERROR

    DisposableEffect(isUpdating) {
        val activity = context.findActivity()
        if (isUpdating && activity != null) {
            activity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            android.util.Log.d("UpdateScreen", "Keep screen on: enabled")
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            android.util.Log.d("UpdateScreen", "Keep screen on: disabled")
        }
    }

    LaunchedEffect(connectionState.isConnected) {
        if (connectionState.isConnected) {
            viewModel.loadCurrentFirmware()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadReleases()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Current firmware info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    "Current Firmware",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))

                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    InfoRow("Version", uiState.currentFirmware.version)
                    InfoRow("Build Date", uiState.currentFirmware.buildDate)
                    InfoRow("Chip", uiState.currentFirmware.chipModel)
                    InfoRow("Free Heap", "${uiState.currentFirmware.freeHeap} bytes")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Update progress
        if (uiState.updateProgress.state != UpdateState.IDLE) {
            UpdateProgressCard(
                progress = uiState.updateProgress,
                onDismiss = { viewModel.resetUpdateState() }
            )
            Spacer(Modifier.height(16.dp))
        }

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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(error, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Filled.Close, "Dismiss")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // GitHub Releases
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Available Releases (${uiState.releases.size})",
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(onClick = { viewModel.loadReleases() }) {
                Icon(Icons.Filled.Refresh, "Refresh")
            }
        }

        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(8.dp))

        if (uiState.isLoadingReleases) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.releases.isEmpty()) {
            Text(
                text = "No releases available",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            uiState.releases.forEach { release ->
                ReleaseCard(
                    release = release,
                    currentVersion = uiState.currentFirmware.version,
                    isUpdating = uiState.updateProgress.state != UpdateState.IDLE,
                    onInstall = {
                        viewModel.downloadAndInstall(release, context.cacheDir)
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        Spacer(Modifier.height(16.dp))

        // Install from file button
        OutlinedButton(
            onClick = { filePickerLauncher.launch("application/octet-stream") },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.updateProgress.state == UpdateState.IDLE
        ) {
            Icon(Icons.Filled.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Install from local file")
        }
    }
}

@Composable
private fun UpdateProgressCard(
    progress: com.canbox.manager.domain.model.UpdateProgress,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (progress.state) {
                UpdateState.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                UpdateState.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (progress.state) {
                    UpdateState.DOWNLOADING, UpdateState.FLASHING -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    UpdateState.SUCCESS -> {
                        Icon(Icons.Filled.CheckCircle, null, tint = StatusConnected)
                    }
                    UpdateState.ERROR -> {
                        Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        Icon(Icons.Filled.Info, null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    progress.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (progress.state == UpdateState.SUCCESS || progress.state == UpdateState.ERROR) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "Dismiss")
                    }
                }
            }

            if (progress.state == UpdateState.DOWNLOADING || progress.state == UpdateState.FLASHING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(progress.progress * 100).toInt()}% - ${progress.bytesTransferred / 1024} KB / ${progress.totalBytes / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReleaseCard(
    release: GitHubRelease,
    currentVersion: String,
    isUpdating: Boolean,
    onInstall: () -> Unit
) {
    val isCurrentVersion = release.version.equals(currentVersion, ignoreCase = true) ||
            release.tagName.equals("V$currentVersion", ignoreCase = true)
    val firmwareAsset = release.firmwareAsset

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentVersion) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isCurrentVersion) Icons.Filled.CheckCircle else Icons.Filled.NewReleases,
                    contentDescription = null,
                    tint = if (isCurrentVersion) StatusConnected else StatusWarning
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        release.tagName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        release.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isCurrentVersion && firmwareAsset != null) {
                    Button(
                        onClick = onInstall,
                        enabled = !isUpdating,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Install")
                    }
                } else if (isCurrentVersion) {
                    Text(
                        "Installed",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusConnected
                    )
                }
            }

            if (firmwareAsset != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${firmwareAsset.name} (${firmwareAsset.size / 1024} KB)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (release.prerelease) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pre-release",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusWarning
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Find the Activity from a Context (handles wrapped contexts)
 */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}
