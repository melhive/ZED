package com.melhive.zenith.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.melhive.zenith.AppContainer
import com.melhive.zenith.ZenithApp
import com.melhive.zenith.ui.components.ConfirmationDialog
import com.melhive.zenith.ui.screens.ChatScreen
import com.melhive.zenith.ui.screens.DashboardScreen
import com.melhive.zenith.ui.screens.DiagnosticsScreen
import com.melhive.zenith.ui.screens.FilesScreen
import com.melhive.zenith.ui.screens.LiveScreen
import com.melhive.zenith.ui.screens.MemoryScreen
import com.melhive.zenith.ui.screens.ModelsScreen
import com.melhive.zenith.ui.screens.MoreScreen
import com.melhive.zenith.ui.screens.SettingsScreen
import com.melhive.zenith.ui.screens.SkillsScreen
import com.melhive.zenith.ui.screens.TaskDetailScreen
import com.melhive.zenith.ui.screens.TasksScreen
import com.melhive.zenith.ui.theme.ZenithColors

object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val LIVE = "live"
    const val TASKS = "tasks"
    const val TASK = "task/{id}"
    const val MORE = "more"
    const val MEMORY = "memory"
    const val MODELS = "models"
    const val FILES = "files"
    const val SKILLS = "skills"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    fun task(id: Long) = "task/$id"
    fun chat(conversationId: Long?) = if (conversationId == null) CHAT else "$CHAT?id=$conversationId"
}

@Composable
fun rememberContainer(): AppContainer = (LocalContext.current.applicationContext as ZenithApp).container

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home),
    Tab(Routes.CHAT, "Chat", Icons.Outlined.Chat),
    Tab(Routes.LIVE, "Live", Icons.Outlined.GraphicEq),
    Tab(Routes.TASKS, "Tasks", Icons.Outlined.TaskAlt),
    Tab(Routes.MORE, "More", Icons.Outlined.Apps),
)

fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ZenithRoot(requestedRoute: String?, onRouteConsumed: () -> Unit) {
    val container = rememberContainer()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val confirmations by container.confirmations.requests.collectAsStateWithLifecycle()
    // Hide the bottom bar while the keyboard is open so text fields sit directly on the keyboard.
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible

    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) { nav.navigateTab(requestedRoute); onRouteConsumed() }
    }

    Scaffold(
        containerColor = ZenithColors.Background,
        bottomBar = {
            if (route != Routes.LIVE && !imeVisible) {
                NavigationBar(containerColor = ZenithColors.Surface) {
                    tabs.forEach { tab ->
                        val selected = route?.substringBefore('?') == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = ZenithColors.Cyan.copy(alpha = 0.15f), selectedIconColor = ZenithColors.Cyan, selectedTextColor = ZenithColors.Cyan),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val contentPadding = if (route == Routes.LIVE) androidx.compose.foundation.layout.PaddingValues() else padding
        // consumeWindowInsets prevents screens' imePadding() from adding the navigation-bar space twice.
        Box(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { DashboardScreen(onNavigate = { nav.navigate(it) }, onTab = { nav.navigateTab(it) }) }
                composable(
                    "${Routes.CHAT}?id={id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id")?.takeIf { it > 0 }
                    ChatScreen(initialConversationId = id, onOpenLive = { nav.navigateTab(Routes.LIVE) }, onOpenModels = { nav.navigate(Routes.MODELS) })
                }
                composable(Routes.LIVE) { LiveScreen(onClose = { if (!nav.popBackStack()) nav.navigateTab(Routes.HOME) }, onOpenTasks = { nav.navigateTab(Routes.TASKS) }) }
                composable(Routes.TASKS) { TasksScreen(onOpen = { nav.navigate(Routes.task(it)) }) }
                composable(Routes.TASK, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    TaskDetailScreen(taskId = entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
                }
                composable(Routes.MORE) { MoreScreen(onNavigate = { nav.navigate(it) }) }
                composable(Routes.MEMORY) { MemoryScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.MODELS) { ModelsScreen(onBack = { nav.popBackStack() }, onOpenTasks = { nav.navigateTab(Routes.TASKS) }) }
                composable(Routes.FILES) { FilesScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.SKILLS) { SkillsScreen(onBack = { nav.popBackStack() }) }
                composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
                composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }

    confirmations.firstOrNull()?.let { req ->
        ConfirmationDialog(req) { approved -> container.confirmations.answer(req.id, approved) }
    }
}
