package com.canbox.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.canbox.manager.ui.theme.Primary
import com.canbox.manager.ui.theme.SurfaceVariant

@Composable
fun SteeringIndicator(
    angle: Int,
    modifier: Modifier = Modifier,
    maxAngle: Int = 500
) {
    val normalizedPosition = (angle.toFloat() / maxAngle.toFloat()).coerceIn(-1f, 1f)
    // Convert from -1..1 to 0..1 where 0.5 is center
    val position = (normalizedPosition + 1f) / 2f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left arrow
        Text(
            text = "<",
            style = MaterialTheme.typography.titleLarge,
            color = if (angle < -10) Primary else MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Track with indicator
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            // Center marker
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            )

            // Position indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth(position.coerceIn(0f, 1f))
                    .fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.CenterEnd)
                        .offset(x = 8.dp)
                        .clip(CircleShape)
                        .background(Primary)
                )
            }
        }

        // Right arrow
        Text(
            text = ">",
            style = MaterialTheme.typography.titleLarge,
            color = if (angle > 10) Primary else MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Angle value
        Text(
            text = "${if (angle >= 0) "+" else ""}$angle°",
            style = MaterialTheme.typography.titleMedium,
            color = Primary,
            modifier = Modifier.width(64.dp)
        )
    }
}
