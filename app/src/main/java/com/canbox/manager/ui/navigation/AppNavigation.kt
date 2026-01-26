package com.canbox.manager.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        NavRoute.items.forEach { route ->
            val selected = currentDestination?.hierarchy?.any { it.route == route.route } == true

            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = route.icon,
                        contentDescription = route.title
                    )
                },
                label = { Text(route.title) },
                selected = selected,
                onClick = {
                    navController.navigate(route.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}
