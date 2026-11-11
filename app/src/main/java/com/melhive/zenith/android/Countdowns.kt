package com.melhive.zenith.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Timers and alarms ZED set, so it can say exactly how long is left. The clock
 * app doesn't share its timers, so ZED keeps its own record (end times only).
 */
class Countdowns(context: Context) {
    data class Entry(val kind: String, val label: String, val endAt: Long)

    private val prefs = context.getSharedPreferences("zed_countdowns", Context.MODE_PRIVATE)

    fun add(kind: String, label: String, endAt: Long) {
        val list = active() + Entry(kind, label, endAt)
        prefs.edit().putString("list", JSONArray(list.map { JSONObject().put("kind", it.kind).put("label", it.label).put("endAt", it.endAt) }).toString()).apply()
    }

    /** Entries that haven't ended yet (finished ones are dropped). */
    fun active(now: Long = System.currentTimeMillis()): List<Entry> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Entry(it.getString("kind"), it.optString("label"), it.getLong("endAt")) }
    }.getOrDefault(emptyList()).filter { it.endAt > now }.sortedBy { it.endAt }
}
