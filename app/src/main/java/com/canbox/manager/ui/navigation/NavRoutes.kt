package com.canbox.manager.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class NavRoute(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Live : NavRoute("live", "Live", Icons.Filled.Speed)
    data object CanConfig : NavRoute("can_config", "CAN Config", Icons.Filled.Settings)
    data object Calibration : NavRoute("calibration", "Calibration", Icons.Filled.Tune)
    data object Update : NavRoute("update", "Update", Icons.Filled.SystemUpdate)
    data object Debug : NavRoute("debug", "Debug", Icons.Filled.BugReport)

    companion object {
        val items = listOf(Live, CanConfig, Calibration, Update, Debug)
    }
}
