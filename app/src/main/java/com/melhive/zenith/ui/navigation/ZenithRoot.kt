package com.melhive.zenith.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import com.melhive.zenith.ui.components.hudBackground
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
    const val VOICE = "voice"
    const val ROUTINES = "routines"
    const val SCHEDULE = "schedule"
    const val WHATS_NEW = "whats_new"
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
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) { nav.navigateTab(requestedRoute); onRouteConsumed() }
    }

    Scaffold(
        containerColor = ZenithColors.Background,
        bottomBar = {
            if (route != Routes.LIVE && !imeVisible) {
                NavigationBar(
                    containerColor = ZenithColors.Surface.copy(alpha = 0.97f),
                    modifier = Modifier.drawBehind {
                        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, ZenithColors.Cyan.copy(alpha = 0.6f), Color.Transparent)),
                            androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.5.dp.toPx())
                    },
                ) {
                    tabs.forEach { tab ->
                        val selected = route?.substringBefore('?') == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label.uppercase(), letterSpacing = 1.5.sp, style = MaterialTheme.typography.labelSmall) },
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
            NavHost(
                nav, startDestination = Routes.HOME,
                enterTransition = { NavMotion.enter(initialState.destination.route, targetState.destination.route) },
                exitTransition = { NavMotion.exit(initialState.destination.route, targetState.destination.route) },
                popEnterTransition = { NavMotion.popEnter(initialState.destination.route, targetState.destination.route) },
                popExitTransition = { NavMotion.popExit(initialState.destination.route, targetState.destination.route) },
            ) {
                screen(Routes.HOME) { DashboardScreen(onNavigate = { nav.navigate(it) }, onTab = { nav.navigateTab(it) }) }
                screen(
                    "${Routes.CHAT}?id={id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id")?.takeIf { it > 0 }
                    ChatScreen(initialConversationId = id, onOpenLive = { nav.navigateTab(Routes.LIVE) }, onOpenModels = { nav.navigate(Routes.MODELS) })
                }
                screen(Routes.LIVE) { LiveScreen(onClose = { if (!nav.popBackStack()) nav.navigateTab(Routes.HOME) }, onOpenTasks = { nav.navigateTab(Routes.TASKS) }) }
                screen(Routes.TASKS) { TasksScreen(onOpen = { nav.navigate(Routes.task(it)) }) }
                screen(Routes.TASK, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    TaskDetailScreen(taskId = entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
                }
                screen(Routes.MORE) { MoreScreen(onNavigate = { nav.navigate(it) }) }
                screen(Routes.MEMORY) { MemoryScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.MODELS) { ModelsScreen(onBack = { nav.popBackStack() }, onOpenTasks = { nav.navigateTab(Routes.TASKS) }) }
                screen(Routes.FILES) { FilesScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.SKILLS) { SkillsScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
                screen(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.SCHEDULE) { MemoryScreen(onBack = { nav.popBackStack() }, initialTab = 4) }
                screen(Routes.WHATS_NEW) { com.melhive.zenith.ui.screens.WhatsNewScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.ROUTINES) { com.melhive.zenith.ui.screens.RoutinesScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.VOICE) { com.melhive.zenith.ui.screens.VoiceScreen(onBack = { nav.popBackStack() }, onOpenTasks = { nav.navigateTab(Routes.TASKS) }) }
            }
        }
    }

    // After an update: show what's new once.
    val settingsState by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val st = settingsState
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (st != null && st.lastSeenVersion != com.melhive.zenith.BuildConfig.VERSION_NAME && (st.lastSeenVersion != null || st.onboardingDone)) {
        fun seen() = scope.launch { container.settings.setLastSeenVersion(com.melhive.zenith.BuildConfig.VERSION_NAME) }
        com.melhive.zenith.ui.screens.WhatsNewDialog(st.lastSeenVersion, onSeeAll = { seen(); nav.navigate(Routes.WHATS_NEW) }, onDismiss = { seen() })
    } else if (st != null && st.lastSeenVersion == null && !st.onboardingDone) {
        // Fresh install: nothing to announce.
        LaunchedEffect(Unit) { container.settings.setLastSeenVersion(com.melhive.zenith.BuildConfig.VERSION_NAME) }
    }

    confirmations.firstOrNull()?.let { req ->
        ConfirmationDialog(req) { approved -> container.confirmations.answer(req.id, approved) }
    }
}

/** A destination with its own opaque background, so sliding screens never show through each other. */
private fun androidx.navigation.NavGraphBuilder.screen(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    Box(Modifier.fillMaxSize().hudBackground()) { content(entry) }
}

/**
 * Screen motion without fades: pushed screens glide in from the right while the
 * previous one drifts a little to the left (and back on Back); tab switches
 * slide in the tab's direction; ZED LIVE rises from the bottom.
 */
private object NavMotion {
    private val emphasized = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private fun <T> spec() = androidx.compose.animation.core.tween<T>(durationMillis = 420, easing = emphasized)
    private fun specOffset() = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 420, easing = emphasized)
    private val tabOrder = listOf(Routes.HOME, Routes.CHAT, Routes.LIVE, Routes.TASKS, Routes.MORE)
    private fun tab(route: String?) = tabOrder.indexOf(route?.substringBefore('?'))
    private fun isLive(route: String?) = route == Routes.LIVE

    fun enter(from: String?, to: String?): androidx.compose.animation.EnterTransition = when {
        isLive(to) -> androidx.compose.animation.slideInVertically(specOffset()) { it }
        isLive(from) -> androidx.compose.animation.slideInVertically(specOffset()) { -it / 6 }
        tab(from) >= 0 && tab(to) >= 0 -> androidx.compose.animation.slideInHorizontally(specOffset()) { w -> if (tab(to) > tab(from)) w else -w }
        else -> androidx.compose.animation.slideInHorizontally(specOffset()) { it }
    }

    fun exit(from: String?, to: String?): androidx.compose.animation.ExitTransition = when {
        isLive(to) -> androidx.compose.animation.slideOutVertically(specOffset()) { -it / 6 }
        isLive(from) -> androidx.compose.animation.slideOutVertically(specOffset()) { it }
        tab(from) >= 0 && tab(to) >= 0 -> androidx.compose.animation.slideOutHorizontally(specOffset()) { w -> if (tab(to) > tab(from)) -w else w }
        else -> androidx.compose.animation.slideOutHorizontally(specOffset()) { -it / 4 }
    }

    fun popEnter(from: String?, to: String?): androidx.compose.animation.EnterTransition = when {
        isLive(from) -> androidx.compose.animation.slideInVertically(specOffset()) { -it / 6 }
        tab(from) >= 0 && tab(to) >= 0 -> enter(from, to)
        else -> androidx.compose.animation.slideInHorizontally(specOffset()) { -it / 4 }
    }

    fun popExit(from: String?, to: String?): androidx.compose.animation.ExitTransition = when {
        isLive(from) -> androidx.compose.animation.slideOutVertically(specOffset()) { it }
        tab(from) >= 0 && tab(to) >= 0 -> exit(from, to)
        else -> androidx.compose.animation.slideOutHorizontally(specOffset()) { it }
    }
}
