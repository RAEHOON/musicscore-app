package com.scorebook.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.scorebook.feature.imagescore.ImageScoreScreen
import com.scorebook.feature.library.LibraryScreen
import com.scorebook.feature.pageeditor.PageEditorScreen
import com.scorebook.feature.practice.PracticeScreen
import com.scorebook.feature.setlist.SetlistDetailScreen
import com.scorebook.feature.setlist.SetlistsScreen
import com.scorebook.feature.settings.SettingsScreen
import com.scorebook.feature.viewer.ViewerScreen

private data class TopLevel(val route: Any, val label: String, val icon: ImageVector)

private val topLevels = listOf(
    TopLevel(LibraryRoute, "악보", Icons.Default.LibraryMusic),
    TopLevel(SetlistsRoute, "셋리스트", Icons.AutoMirrored.Filled.QueueMusic),
    TopLevel(PracticeRoute, "연습 기록", Icons.Default.Timer),
    TopLevel(SettingsRoute, "설정", Icons.Default.Settings),
)

private fun NavDestination?.isTopLevel(route: Any): Boolean = this?.hasRoute(route::class) == true

@Composable
fun AppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showNav = destination == null || topLevels.any { destination.isTopLevel(it.route) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val pendingImages by viewModel.pendingImages.collectAsStateWithLifecycle()
    LaunchedEffect(pendingImages) {
        if (pendingImages.isNotEmpty() && destination?.hasRoute(ImageScoreRoute::class) != true) {
            nav.navigate(ImageScoreRoute)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            if (showNav && wide) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    topLevels.forEach { item ->
                        NavigationRailItem(
                            selected = destination.isTopLevel(item.route),
                            onClick = { nav.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { AppNavHost(nav) }
                    if (showNav && !wide) {
                        NavigationBar {
                            topLevels.forEach { item ->
                                NavigationBarItem(
                                    selected = destination.isTopLevel(item.route),
                                    onClick = { nav.navigateTopLevel(item.route) },
                                    icon = { Icon(item.icon, contentDescription = null) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppNavHost(nav: NavHostController) {
    NavHost(navController = nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                onOpenScore = { nav.navigate(ViewerRoute(it)) },
                onOpenPageEditor = { nav.navigate(PageEditorRoute(it)) },
                onCreateFromImages = { nav.navigate(ImageScoreRoute) },
            )
        }
        composable<ViewerRoute> { entry ->
            val route = entry.toRoute<ViewerRoute>()
            ViewerScreen(
                onBack = { nav.popBackStack() },
                onOpenScore = { scoreId, setlistId, index ->
                    nav.navigate(ViewerRoute(scoreId, setlistId, index)) {
                        popUpTo<ViewerRoute> { inclusive = true }
                    }
                },
                onOpenPageEditor = { nav.navigate(PageEditorRoute(route.scoreId)) },
            )
        }
        composable<PageEditorRoute> {
            PageEditorScreen(
                onBack = { nav.popBackStack() },
                onOpenScore = { id ->
                    nav.navigate(ViewerRoute(id)) { popUpTo<PageEditorRoute> { inclusive = true } }
                },
            )
        }
        composable<ImageScoreRoute> {
            ImageScoreScreen(
                onBack = { nav.popBackStack() },
                onCreated = { id ->
                    nav.navigate(ViewerRoute(id)) { popUpTo<ImageScoreRoute> { inclusive = true } }
                },
            )
        }
        composable<SetlistsRoute> {
            SetlistsScreen(onOpen = { nav.navigate(SetlistDetailRoute(it)) })
        }
        composable<SetlistDetailRoute> {
            SetlistDetailScreen(
                onBack = { nav.popBackStack() },
                onPlay = { setlistId, index, scoreId -> nav.navigate(ViewerRoute(scoreId, setlistId, index)) },
            )
        }
        composable<PracticeRoute> {
            PracticeScreen(onOpenScore = { nav.navigate(ViewerRoute(it)) })
        }
        composable<SettingsRoute> {
            SettingsScreen()
        }
    }
}
