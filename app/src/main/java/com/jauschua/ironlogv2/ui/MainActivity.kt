package com.jauschua.ironlogv2.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.ui.screens.bands.BandsScreen
import com.jauschua.ironlogv2.ui.screens.capture.CaptureScreen
import com.jauschua.ironlogv2.ui.screens.movement_detail.MovementDetailScreen
import com.jauschua.ironlogv2.ui.screens.autoregulate.AutoregulateScreen
import com.jauschua.ironlogv2.ui.screens.movements.MovementsListScreen
import com.jauschua.ironlogv2.ui.screens.wizard.WizardScreen
import com.jauschua.ironlogv2.ui.theme.IronLogV2Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IronLogV2Theme {
                RootScaffold()
            }
        }
    }
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val navTarget: String = route,
)

private val TABS = listOf(
    Tab(Routes.MOVEMENTS, "Movements", Icons.Filled.FitnessCenter),
    Tab(Routes.BANDS, "Bands", Icons.Filled.Sync),
    Tab(Routes.AUTOREGULATE, "Autoregulate", Icons.Filled.Calculate),
    Tab(Routes.CAPTURE, "Capture", Icons.Filled.PlayArrow),
    Tab(Routes.WIZARD, "Setup", Icons.Filled.Tune, navTarget = Routes.wizard()),
)

@Composable
private fun RootScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            nav.navigate(tab.navTarget) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { inner ->
        val container = (LocalContext.current.applicationContext as IronLogV2Application).container

        NavHost(
            navController = nav,
            startDestination = Routes.MOVEMENTS,
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            composable(Routes.MOVEMENTS) {
                MovementsListScreen(onMovementClick = { id ->
                    nav.navigate(Routes.movementDetail(id))
                })
            }
            composable(
                route = Routes.MOVEMENT_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) {
                MovementDetailScreen(
                    onBack = { nav.popBackStack() },
                    onTryAutoregulate = { id ->
                        container.autoregPrefill.value = id
                        nav.navigate(Routes.AUTOREGULATE) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.BANDS) { BandsScreen() }
            composable(Routes.AUTOREGULATE) { AutoregulateScreen() }
            composable(Routes.CAPTURE) { CaptureScreen() }
            composable(
                route = Routes.WIZARD,
                arguments = listOf(
                    navArgument("programId") {
                        type = NavType.IntType
                        defaultValue = Routes.DEFAULT_PROGRAM_ID
                    },
                ),
            ) { entry ->
                val programId = entry.arguments?.getInt("programId") ?: Routes.DEFAULT_PROGRAM_ID
                WizardScreen(
                    programId = programId,
                    onStarted = {
                        nav.navigate(Routes.CAPTURE) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }
}
