package com.melhive.zed.agents

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.melhive.zed.AppContainer
import com.melhive.zed.core.understanding.Awareness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/**
 * The phone's side of the awareness brief: time, place, battery, connection,
 * the next calendar event and what is counting down. Everything here is read
 * locally in a few milliseconds; anything slow is skipped rather than waited for.
 */
class AwarenessCollector(private val context: Context, private val c: AppContainer) {
    suspend fun collect(): Awareness = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now()
        val nowMs = System.currentTimeMillis()
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.let { b ->
            val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null
        }
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
        val online = runCatching { com.melhive.zed.android.DeviceInfo.snapshot(context).online }.getOrNull()
        val place = c.settings.current().weatherCity.ifBlank { null } ?: Awareness.placeFromZone(now.zone.id)
        val next = withTimeoutOrNull(250) { runCatching { CalendarReader(context).nextEvent(nowMs) }.getOrNull() }
        val countdowns = buildList {
            runCatching { com.melhive.zed.android.Countdowns(context).active(nowMs) }.getOrDefault(emptyList()).forEach { e ->
                add((e.kind.replaceFirstChar { it.uppercase() } + if (e.label.isNotBlank()) " “${e.label}”" else "") to e.endAt)
            }
            runCatching { c.db.reminders().scheduled() }.getOrDefault(emptyList()).filter { it.triggerAt > nowMs }.sortedBy { it.triggerAt }.take(2).forEach { r ->
                add("Reminder #${r.id} “${r.text}”" to r.triggerAt)
            }
        }.sortedBy { it.second }
        Awareness(now = now, place = place, batteryPercent = level, charging = charging, online = online, nextEvent = next, countdowns = countdowns)
    }
}
