package com.canbox.manager.ui.screens.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.canbox.manager.data.usb.UsbConnectionState
import com.canbox.manager.domain.model.DoorStatus
import com.canbox.manager.domain.model.LightStatus
import com.canbox.manager.domain.model.VehicleData
import com.canbox.manager.domain.model.VehicleMode
import com.canbox.manager.ui.components.GaugeWidget
import com.canbox.manager.ui.components.SteeringIndicator
import com.canbox.manager.ui.theme.*
import org.koin.androidx.compose.koinViewModel

@Composable
fun LiveScreen(
    viewModel: LiveViewModel = koinViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val vehicleData by viewModel.vehicleData.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Start/stop polling based on lifecycle (screen visibility)
    DisposableEffect(lifecycleOwner, connectionState) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (connectionState.isConnected) {
                        viewModel.startPolling()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.stopPolling()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Start polling immediately if already connected and resumed
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            connectionState.isConnected) {
            viewModel.startPolling()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopPolling()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (!connectionState.isConnected) {
            DisconnectedOverlay()
        } else {
            LiveDashboard(vehicleData)
        }
    }
}

@Composable
private fun DisconnectedOverlay() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.UsbOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "USB Not Connected",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Connect your ESP32 CANBox via USB",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LiveDashboard(data: VehicleData) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top row: Main gauges
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GaugeWidget(
                value = data.rpm,
                label = "RPM",
                maxValue = 8000,
                color = GaugeRpm,
                modifier = Modifier.weight(1f)
            )
            GaugeWidget(
                value = data.speed,
                label = "km/h",
                maxValue = 200,
                color = GaugeSpeed,
                modifier = Modifier.weight(1f)
            )
            GaugeWidget(
                value = data.voltage,
                label = "Battery",
                unit = "V",
                maxValue = 16f,
                color = GaugeVoltage,
                modifier = Modifier.weight(1f)
            )
            GaugeWidget(
                value = data.temperature,
                label = "Temp",
                unit = "°C",
                maxValue = 120,
                color = GaugeTemperature,
                modifier = Modifier.weight(1f)
            )
            GaugeWidget(
                value = data.fuelLevel,
                label = "Fuel",
                unit = "L",
                maxValue = 50,
                color = GaugeFuel,
                modifier = Modifier.weight(1f)
            )
            GaugeWidget(
                value = data.dte,
                label = "DTE",
                unit = "km",
                maxValue = 600,
                color = Primary,
                modifier = Modifier.weight(1f)
            )
        }

        // Middle: Steering indicator
        SteeringIndicator(
            angle = data.steering,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )

        // Bottom row: Doors, Lights, Status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Doors panel
            DoorStatusPanel(
                doors = data.doors,
                modifier = Modifier.weight(1f)
            )

            // Lights panel
            LightStatusPanel(
                lights = data.lights,
                modifier = Modifier.weight(1f)
            )
        }

        // Status bar
        StatusBar(
            mode = data.mode,
            configFile = data.configFile,
            handbrake = data.handbrake,
            reverse = data.reverse
        )
    }
}

@Composable
private fun DoorStatusPanel(
    doors: DoorStatus,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                text = "Doors",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DoorIndicator("FL", doors.frontLeft)
                DoorIndicator("FR", doors.frontRight)
                DoorIndicator("RL", doors.rearLeft)
                DoorIndicator("RR", doors.rearRight)
                DoorIndicator("TRK", doors.trunk)
            }
        }
    }
}

@Composable
private fun DoorIndicator(label: String, isOpen: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isOpen) IndicatorWarning else IndicatorOff)
                .border(
                    width = 1.dp,
                    color = if (isOpen) IndicatorWarning else Color.Gray,
                    shape = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isOpen) Icons.Filled.DoorFront else Icons.Filled.DoorFront,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isOpen) Color.White else Color.Gray
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isOpen) IndicatorWarning else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LightStatusPanel(
    lights: LightStatus,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                text = "Lights",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LightIndicator(Icons.Filled.LightMode, "Park", lights.parking)
                LightIndicator(Icons.Filled.Light, "Low", lights.lowBeam)
                LightIndicator(Icons.Filled.Highlight, "High", lights.highBeam)
                LightIndicator(Icons.Filled.TurnLeft, "L", lights.leftIndicator, IndicatorWarning)
                LightIndicator(Icons.Filled.TurnRight, "R", lights.rightIndicator, IndicatorWarning)
            }
        }
    }
}

@Composable
private fun LightIndicator(
    icon: ImageVector,
    label: String,
    isOn: Boolean,
    onColor: Color = IndicatorOn
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (isOn) onColor else IndicatorOff),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isOn) Color.White else Color.Gray
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isOn) onColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusBar(
    mode: VehicleMode,
    configFile: String?,
    handbrake: Boolean,
    reverse: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mode with config file
        val modeText = if (!configFile.isNullOrEmpty()) {
            "${mode.name} ($configFile)"
        } else {
            mode.name
        }
        Text(
            text = modeText,
            style = MaterialTheme.typography.bodyMedium,
            color = when (mode) {
                VehicleMode.REAL -> StatusConnected
                VehicleMode.MOCK -> StatusWarning
                VehicleMode.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Handbrake
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (handbrake) StatusDisconnected else IndicatorOff)
                )
                Text(
                    text = "Handbrake",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (handbrake) StatusDisconnected else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Reverse
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (reverse) StatusWarning else IndicatorOff)
                )
                Text(
                    text = "Reverse",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (reverse) StatusWarning else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
