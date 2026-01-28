package com.canbox.manager.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.canbox.manager.ui.screens.calibration.CalibrationScreen
import com.canbox.manager.ui.screens.canconfig.CanConfigScreen
import com.canbox.manager.ui.screens.debug.DebugScreen
import com.canbox.manager.ui.screens.live.LiveScreen
import com.canbox.manager.ui.screens.update.UpdateScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = NavRoute.Live.route,
        modifier = modifier
    ) {
        composable(NavRoute.Live.route) {
            LiveScreen()
        }
        composable(NavRoute.CanConfig.route) {
            CanConfigScreen()
        }
        composable(NavRoute.Calibration.route) {
            CalibrationScreen()
        }
        composable(NavRoute.Update.route) {
            UpdateScreen()
        }
        composable(NavRoute.Debug.route) {
            DebugScreen()
        }
    }
}

@Composable
fun TopNavigationBar(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val selectedIndex = NavRoute.items.indexOfFirst { route ->
        currentDestination?.hierarchy?.any { it.route == route.route } == true
    }.coerceAtLeast(0)

    // Dynamic tab height based on screen aspect ratio
    val configuration = LocalConfiguration.current
    val screenRatio = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
    // 16:9 landscape = 1.77, S8 landscape ~2.05
    val tabHeight = if (screenRatio < 1.9f) 48.dp else 36.dp
    val iconSize = if (screenRatio < 1.9f) 18.dp else 14.dp

    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier
            .fillMaxWidth()
            .height(tabHeight),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        NavRoute.items.forEachIndexed { index, route ->
            Tab(
                selected = index == selectedIndex,
                onClick = {
                    navController.navigate(route.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.height(tabHeight),
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = route.icon,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = route.title,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
