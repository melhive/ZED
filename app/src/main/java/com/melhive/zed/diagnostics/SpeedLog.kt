package com.melhive.zed.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * How long each step of a conversation takes on this phone, measured on real use
 * (Diagnostics → Speed). The last [KEEP] measurements of each step are kept on the device.
 */
object SpeedLog {
    enum class Stage(val label: String, val detail: String) {
        WAKE("Wake → orb", "From “Hey Zed” being heard to the floating orb opening"),
        HEARING("Hearing", "From the end of your sentence to ZED having the words"),
        NATIVE("Instant commands", "A command ZED handles itself, start to finish"),
        FIRST_WORD("Gemma's first word", "From your request to the model's first word"),
        ANSWER("Gemma's full answer", "From your request to the end of the model's answer"),
        VOICE("Voice start", "From a sentence being ready to ZED starting to say it"),
    }

    private const val KEEP = 30
    private const val PREFS = "zed_speed"
    @Volatile private var app: Context? = null
    private val data = HashMap<Stage, ArrayDeque<Long>>()

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        runCatching {
            val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("v", "{}") ?: "{}")
            for (s in Stage.entries) o.optJSONArray(s.name)?.let { a -> data[s] = ArrayDeque((0 until a.length()).map { a.getLong(it) }) }
        }
    }

    fun record(stage: Stage, ms: Long) {
        if (ms < 0 || ms > 120_000) return
        synchronized(data) {
            val q = data.getOrPut(stage) { ArrayDeque() }
            q.addLast(ms); while (q.size > KEEP) q.removeFirst()
            val o = JSONObject(); data.forEach { (k, v) -> o.put(k.name, JSONArray(v.toList())) }
            app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("v", o.toString())?.apply()
        }
    }

    data class Summary(val stage: Stage, val median: Long, val best: Long, val last: Long, val count: Int)

    fun summary(): List<Summary> = synchronized(data) {
        Stage.entries.mapNotNull { s ->
            val v = data[s]?.toList()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val sorted = v.sorted()
            Summary(s, sorted[sorted.size / 2], sorted.first(), v.last(), v.size)
        }
    }

    fun clear() = synchronized(data) { data.clear(); app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply() }
}
