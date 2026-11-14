package com.melhive.zed.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import com.melhive.zed.ui.components.hudBackground
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
import com.melhive.zed.AppContainer
import com.melhive.zed.ZedApp
import com.melhive.zed.ui.components.ConfirmationDialog
import com.melhive.zed.ui.screens.ChatScreen
import com.melhive.zed.ui.screens.DashboardScreen
import com.melhive.zed.ui.screens.DiagnosticsScreen
import com.melhive.zed.ui.screens.FilesScreen
import com.melhive.zed.ui.screens.LiveScreen
import com.melhive.zed.ui.screens.MemoryScreen
import com.melhive.zed.ui.screens.ModelsScreen
import com.melhive.zed.ui.screens.MoreScreen
import com.melhive.zed.ui.screens.SettingsScreen
import com.melhive.zed.ui.screens.SkillsScreen
import com.melhive.zed.ui.screens.TaskDetailScreen
import com.melhive.zed.ui.screens.TasksScreen
import com.melhive.zed.ui.theme.ZedColors

object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val LIVE = "live"
    const val EYE = "eye"
    const val PEOPLE = "people"
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
    const val TERMS = "terms"
    const val PRIVACY = "privacy"
    const val WHATS_NEW = "whats_new"
    const val BRAIN = "brain"
    const val BACKUP = "backup"
    const val MEMORY_TAB = "memory/{tab}"
    fun memoryTab(tab: Int) = "memory/$tab"
    const val SETTINGS_SECTION = "settings/{section}"
    fun settings(section: String) = "settings/$section"
    fun task(id: Long) = "task/$id"
    fun chat(conversationId: Long?) = if (conversationId == null) CHAT else "$CHAT?id=$conversationId"
}

@Composable
fun rememberContainer(): AppContainer = (LocalContext.current.applicationContext as ZedApp).container

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home),
    Tab(Routes.CHAT, "Chat", Icons.Outlined.Chat),
    Tab(Routes.LIVE, "Live", Icons.Outlined.GraphicEq),
    Tab(Routes.BRAIN, "Brain", Icons.Outlined.Hub),
    Tab(Routes.MORE, "More", Icons.Outlined.Apps),
)

fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/**
 * Opens LIVE (the orb) or Eye mode. The two are one conversation with two faces, so switching replaces
 * the one on screen instead of stacking them. (Stacked, "back to the orb" restored the stack with the
 * eyes still on top, and nothing appeared to happen.)
 */
fun NavHostController.openVoice(route: String) {
    val here = currentDestination?.route
    if (here == route) return
    if (previousBackStackEntry?.destination?.route == route) { popBackStack(); return }
    navigate(route) {
        if (here == Routes.LIVE || here == Routes.EYE) popUpTo(here) { inclusive = true }
        launchSingleTop = true
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ZedRoot(requestedRoute: String?, onRouteConsumed: () -> Unit) {
    val container = rememberContainer()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val confirmations by container.confirmations.requests.collectAsStateWithLifecycle()
    // Hide the bottom bar while the keyboard is open so text fields sit directly on the keyboard.
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(route) { if (route != null) com.melhive.zed.android.CrashReporter.crumb("screen: $route") }
    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null) {
            // A particular conversation ("chat mode" from Eye mode) is opened as itself, not as the last chat tab.
            val here = nav.currentDestination?.route
            when {
                requestedRoute == Routes.LIVE || requestedRoute == Routes.EYE -> nav.openVoice(requestedRoute)
                // "Chat mode" from the orb or the eyes: the call's own conversation replaces that screen.
                requestedRoute.startsWith(Routes.CHAT + "?") -> nav.navigate(requestedRoute) {
                    if (here == Routes.LIVE || here == Routes.EYE) popUpTo(here) { inclusive = true }
                    launchSingleTop = true
                }
                else -> nav.navigateTab(requestedRoute)
            }
            onRouteConsumed()
        }
    }

    Scaffold(
        containerColor = ZedColors.Background,
        bottomBar = {
            if (route != Routes.LIVE && route != Routes.EYE && !imeVisible) {
                val tabView = androidx.compose.ui.platform.LocalView.current
                NavigationBar(
                    containerColor = ZedColors.Surface.copy(alpha = 0.97f),
                ) {
                    tabs.forEach { tab ->
                        val selected = route?.substringBefore('?') == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) runCatching { tabView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                                nav.navigateTab(tab.route)
                            },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label.uppercase(), letterSpacing = 1.5.sp, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = ZedColors.Cyan.copy(alpha = 0.15f), selectedIconColor = ZedColors.Cyan, selectedTextColor = ZedColors.Cyan),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val contentPadding = if (route == Routes.LIVE || route == Routes.EYE) androidx.compose.foundation.layout.PaddingValues() else padding
        // consumeWindowInsets prevents screens' imePadding() from adding the navigation-bar space twice.
        Box(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding)) {
            NavHost(
                nav, startDestination = Routes.HOME,
                enterTransition = { NavMotion.enter(initialState.destination.route, targetState.destination.route) },
                exitTransition = { NavMotion.exit(initialState.destination.route, targetState.destination.route) },
                popEnterTransition = { NavMotion.popEnter(initialState.destination.route, targetState.destination.route) },
                popExitTransition = { NavMotion.popExit(initialState.destination.route, targetState.destination.route) },
            ) {
                screen(Routes.HOME) { DashboardScreen(onNavigate = { if (it == Routes.EYE) nav.openVoice(it) else nav.navigate(it) }, onTab = { nav.navigateTab(it) }) }
                screen(
                    "${Routes.CHAT}?id={id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id")?.takeIf { it > 0 }
                    ChatScreen(initialConversationId = id, onOpenLive = { nav.openVoice(Routes.LIVE) }, onOpenModels = { nav.navigate(Routes.MODELS) }, onOpenEye = { nav.openVoice(Routes.EYE) })
                }
                screen(Routes.LIVE) { LiveScreen(onClose = { if (!nav.popBackStack()) nav.navigateTab(Routes.HOME) }, onOpenTasks = { nav.navigateTab(Routes.TASKS) }, onOpenEye = { nav.openVoice(Routes.EYE) },
                    onOpenChat = { id -> if (id != null) nav.navigate(Routes.chat(id)) { popUpTo(Routes.LIVE) { inclusive = true }; launchSingleTop = true } else nav.navigateTab(Routes.CHAT) }) }
                // Eye mode: ZED's eyes on the whole screen, in landscape.
                screen(Routes.EYE) {
                    com.melhive.zed.ui.screens.EyeScreen(
                        onClose = { if (!nav.popBackStack()) nav.navigateTab(Routes.HOME) },
                        onOpenChat = { id -> if (id != null) nav.navigate(Routes.chat(id)) { popUpTo(Routes.EYE) { inclusive = true }; launchSingleTop = true } else nav.navigateTab(Routes.CHAT) },
                        onOpenLive = { nav.openVoice(Routes.LIVE) },
                    )
                }
                screen(Routes.TASKS) { TasksScreen(onOpen = { nav.navigate(Routes.task(it)) }) }
                screen(Routes.TASK, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    TaskDetailScreen(taskId = entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
                }
                screen(Routes.MORE) { MoreScreen(onNavigate = { nav.navigate(it) }) }
                screen(Routes.MEMORY) { MemoryScreen(onBack = { nav.popBackStack() }, onOpenBrain = { nav.navigate(Routes.BRAIN) }) }
                screen(Routes.MEMORY_TAB, arguments = listOf(navArgument("tab") { type = NavType.IntType })) { entry ->
                    MemoryScreen(onBack = { nav.popBackStack() }, initialTab = entry.arguments?.getInt("tab") ?: 0, onOpenBrain = { nav.navigate(Routes.BRAIN) })
                }
                screen(Routes.BRAIN) { com.melhive.zed.ui.screens.BrainScreen(onBack = { nav.popBackStack() }, onOpenChat = { nav.navigate(Routes.chat(it)) }) }
                screen(Routes.PEOPLE) { com.melhive.zed.ui.screens.PeopleScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.BACKUP) { com.melhive.zed.ui.screens.BackupScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.MODELS) { ModelsScreen(onBack = { nav.popBackStack() }, onOpenTasks = { nav.navigateTab(Routes.TASKS) }) }
                screen(Routes.FILES) { FilesScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.SKILLS) { SkillsScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }) }
                screen(Routes.SETTINGS_SECTION, arguments = listOf(navArgument("section") { type = NavType.StringType })) { entry ->
                    SettingsScreen(onBack = { nav.popBackStack() }, onNavigate = { nav.navigate(it) }, section = entry.arguments?.getString("section"))
                }
                screen(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.TERMS) { com.melhive.zed.ui.screens.TermsScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.PRIVACY) { com.melhive.zed.ui.screens.PrivacyScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.SCHEDULE) { MemoryScreen(onBack = { nav.popBackStack() }, initialTab = 4) }
                screen(Routes.WHATS_NEW) { com.melhive.zed.ui.screens.WhatsNewScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.ROUTINES) { com.melhive.zed.ui.screens.RoutinesScreen(onBack = { nav.popBackStack() }) }
                screen(Routes.VOICE) { com.melhive.zed.ui.screens.VoiceScreen(onBack = { nav.popBackStack() }, onOpenTasks = { nav.navigateTab(Routes.TASKS) }) }
            }
            // On a stand (charging, held sideways): offer Eye mode.
            if (route != Routes.EYE && route != Routes.LIVE && !imeVisible) StandOffer(Modifier.align(androidx.compose.ui.Alignment.TopCenter)) { nav.openVoice(Routes.EYE) }
            // Undo for the last delete, wherever it happened (voice, chat or a delete button).
            com.melhive.zed.ui.components.UndoBar(container, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
    }

    // After an update: show what's new once.
    val settingsState by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val st = settingsState
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (st != null && st.lastSeenVersion != com.melhive.zed.BuildConfig.VERSION_NAME && (st.lastSeenVersion != null || st.onboardingDone)) {
        fun seen() = scope.launch { container.settings.setLastSeenVersion(com.melhive.zed.BuildConfig.VERSION_NAME) }
        com.melhive.zed.ui.screens.WhatsNewDialog(st.lastSeenVersion, onSeeAll = { seen(); nav.navigate(Routes.WHATS_NEW) }, onDismiss = { seen() })
    } else if (st != null && st.lastSeenVersion == null && !st.onboardingDone) {
        // Fresh install: nothing to announce.
        LaunchedEffect(Unit) { container.settings.setLastSeenVersion(com.melhive.zed.BuildConfig.VERSION_NAME) }
    }

    // After a restore was applied on startup: say what came back and what still needs setting up.
    val restoreNotice by container.backup.restoreNotice.collectAsStateWithLifecycle()
    restoreNotice?.let { notice ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { container.backup.restoreNotice.value = null },
            title = { Text("Restore complete") },
            text = { Text(notice, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { container.backup.restoreNotice.value = null }) { Text("OK") } },
            containerColor = ZedColors.SurfaceHigh,
        )
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
    private val tabOrder = listOf(Routes.HOME, Routes.CHAT, Routes.LIVE, Routes.BRAIN, Routes.MORE)
    private fun tab(route: String?) = tabOrder.indexOf(route?.substringBefore('?'))
    private fun isLive(route: String?) = route == Routes.LIVE || route == Routes.EYE

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

/** A small offer to open Eye mode when the phone is charging and held sideways, as it would be on a stand. Shown once per charge. */
@Composable
private fun StandOffer(modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    var charging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var dismissed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        charging = runCatching { context.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true }.getOrDefault(false)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
                charging = intent.action == android.content.Intent.ACTION_POWER_CONNECTED
                if (!charging) dismissed = false
            }
        }
        val filter = android.content.IntentFilter().apply { addAction(android.content.Intent.ACTION_POWER_CONNECTED); addAction(android.content.Intent.ACTION_POWER_DISCONNECTED) }
        runCatching { androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val sideways = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (!charging || !sideways || dismissed) return
    androidx.compose.foundation.layout.Row(
        modifier.padding(top = 10.dp).background(ZedColors.SurfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(50)).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text("On a stand?", color = ZedColors.Muted, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.material3.TextButton(onClick = { dismissed = true; onOpen() }) { Text("Open Eye mode") }
        androidx.compose.material3.TextButton(onClick = { dismissed = true }) { Text("Not now", color = ZedColors.Muted) }
    }
}
