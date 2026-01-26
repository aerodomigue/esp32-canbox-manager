package com.canbox.manager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.canbox.manager.ui.theme.SurfaceVariant

@Composable
fun GaugeWidget(
    value: Int,
    label: String,
    maxValue: Int,
    color: Color,
    modifier: Modifier = Modifier,
    unit: String = ""
) {
    GaugeWidgetImpl(
        valueText = value.toString(),
        label = label,
        unit = unit,
        progress = value.toFloat() / maxValue.toFloat(),
        color = color,
        modifier = modifier
    )
}

@Composable
fun GaugeWidget(
    value: Float,
    label: String,
    maxValue: Float,
    color: Color,
    modifier: Modifier = Modifier,
    unit: String = ""
) {
    GaugeWidgetImpl(
        valueText = "%.1f".format(value),
        label = label,
        unit = unit,
        progress = value / maxValue,
        color = color,
        modifier = modifier
    )
}

@Composable
private fun GaugeWidgetImpl(
    valueText: String,
    label: String,
    unit: String,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Value
        Text(
            text = valueText,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center
        )

        // Unit and Label
        Text(
            text = if (unit.isNotEmpty()) "$unit $label" else label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}
