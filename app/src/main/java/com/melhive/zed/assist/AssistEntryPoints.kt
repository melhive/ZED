package com.melhive.zed.assist

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.melhive.zed.MainActivity
import com.melhive.zed.R
import com.melhive.zed.ZedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Intents that open ZED on a given screen. */
object ZedLaunch {
    const val ROUTE_LIVE = "live"
    const val ROUTE_CHAT = "chat"

    fun intent(context: Context, route: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(context: Context, route: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent(context, route), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The floating assistant overlay (orb + HUD over the current app). */
    fun overlayIntent(context: Context): Intent =
        Intent(context, AssistOverlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun overlayPending(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(context, requestCode, overlayIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** True when ZED is the phone's default digital assistant (Android 10+). */
    fun isDefaultAssistant(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 && runCatching { context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)

    /** Opens the system screen where the default assistant is chosen. */
    fun openAssistantSettings(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** Asks Android 13+ to add the ZED LIVE tile to Quick Settings. */
    fun requestAddTile(context: Context, onResult: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { onResult("Swipe down twice, tap the pencil icon and drag the ZED LIVE tile into place."); return }
        runCatching {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, LiveTileService::class.java),
                context.getString(R.string.tile_live),
                Icon.createWithResource(context, R.drawable.ic_stat_zed),
                context.mainExecutor,
            ) { result ->
                onResult(when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added to Quick Settings."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in Quick Settings."
                    else -> "Tile not added."
                })
            }
        }.onFailure { onResult("Couldn't add the tile automatically: ${it.message}") }
    }
}

/**
 * Handles the system assist gesture (long-press power/home or swipe from a corner)
 * when ZED is the default digital assistant, and voice-command buttons on
 * headsets: opens ZED LIVE, which starts listening immediately.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(ZedLaunch.overlayIntent(this)) }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

/** Quick Settings tile: one tap starts ZED LIVE. */
class LiveTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_live)
            if (Build.VERSION.SDK_INT >= 29) subtitle = getString(R.string.tile_live_subtitle)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(ZedLaunch.overlayPending(this, 701))
            else @Suppress("DEPRECATION") startActivityAndCollapse(ZedLaunch.overlayIntent(this))
        }.onFailure { com.melhive.zed.android.CrashReporter.recordNonFatal("tile", it) }
    }
}

/** Home-screen widget: talk or type to ZED, with the next reminder and open tasks. */
class ZedWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, null)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { render(context, manager, ids, summary(context)) }
            catch (e: Exception) { com.melhive.zed.android.CrashReporter.recordNonFatal("widget", e) }
            finally { pending.finish() }
        }
    }

    companion object {
        /** Re-renders every placed widget (call after reminders or tasks change). */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ZedWidgetProvider::class.java))
            if (ids.isNotEmpty()) context.sendBroadcast(Intent(context, ZedWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }

        private suspend fun summary(context: Context): String {
            val c = (context.applicationContext as? ZedApp)?.container ?: return context.getString(R.string.widget_hint)
            val next = c.db.reminders().scheduled().firstOrNull { it.triggerAt > System.currentTimeMillis() }
            val open = c.db.todos().list(false).size
            val parts = mutableListOf<String>()
            if (next != null) parts += "⏰ ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(next.triggerAt))} ${next.text}"
            if (open > 0) parts += "$open open task${if (open == 1) "" else "s"}"
            return parts.joinToString(" · ").ifEmpty { context.getString(R.string.widget_hint) }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, subtitle: String?) {
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_zed).apply {
                    setTextViewText(R.id.widget_subtitle, subtitle ?: context.getString(R.string.widget_hint))
                    setOnClickPendingIntent(R.id.widget_root, ZedLaunch.pending(context, ZedLaunch.ROUTE_LIVE, 710))
                    setOnClickPendingIntent(R.id.widget_talk, ZedLaunch.overlayPending(context, 711))
                    setOnClickPendingIntent(R.id.widget_type, ZedLaunch.pending(context, ZedLaunch.ROUTE_CHAT, 712))
                }
                manager.updateAppWidget(id, views)
            }
        }
    }
}
