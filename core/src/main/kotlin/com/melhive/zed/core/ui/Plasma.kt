package com.melhive.zed.core.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The shape of the plasma orb: a radius for each of [n] directions around the
 * centre. Pure maths, so it is tested on the JVM and cheap on the phone.
 *
 * - At rest the surface drifts slowly (a few soft harmonics).
 * - [mic] (0..1, the user's voice) adds energy and ripples that travel inward.
 * - [wave] (ZED's speech, loudness per slice) bulges the surface where the
 *   sound is, mirrored left and right so the shape stays balanced.
 */
object PlasmaShape {
    /** Radii as fractions of the base radius (≈ 0.8 … 1.25). [layer] offsets the phase so stacked layers differ. */
    fun radii(n: Int, time: Float, mic: Float = 0f, wave: FloatArray? = null, layer: Int = 0, out: FloatArray = FloatArray(n)): FloatArray {
        val t = time + layer * 1.37f
        val calm = 0.035f + 0.02f * layer
        val energy = mic.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat()
            var r = 1f
            // Slow drift: three soft harmonics at different speeds.
            r += calm * sin(3 * a + t * 0.9f) + calm * 0.7f * sin(5 * a - t * 1.3f) + calm * 0.5f * sin(2 * a + t * 0.5f)
            // Voice: higher harmonics, fast, scaled by loudness.
            if (energy > 0f) r += 0.09f * energy * sin(7 * a + t * 6f) + 0.05f * energy * sin(11 * a - t * 8f)
            // Speech: the loudness of the matching slice of the waveform pushes the surface out.
            if (wave != null && wave.isNotEmpty()) {
                val half = (if (a <= PI) a / PI.toFloat() else (2f * PI.toFloat() - a) / PI.toFloat()) // 0 at the top … 1 at the bottom, both sides
                val pos = half * (wave.size - 1)
                val lo = pos.toInt().coerceIn(0, wave.size - 1); val hi = (lo + 1).coerceAtMost(wave.size - 1)
                val v = wave[lo] + (wave[hi] - wave[lo]) * (pos - lo)
                r += 0.16f * v.coerceIn(0f, 1f)
            }
            out[i] = r.coerceIn(0.78f, 1.3f)
        }
        return out
    }

    /**
     * Inward ripples while listening: [count] rings whose radius (fraction of
     * the outer edge, 1 → 0) moves toward the centre over time, faster with
     * a louder voice. Returns pairs of (radius, alpha).
     */
    fun inwardRipples(time: Float, mic: Float, count: Int = 3): List<Pair<Float, Float>> {
        val speed = 0.35f + 0.9f * mic.coerceIn(0f, 1f)
        return (0 until count).map { k ->
            val phase = ((time * speed + k.toFloat() / count) % 1f + 1f) % 1f
            val radius = 1f - phase                    // outside → in
            val alpha = sin(phase * PI.toFloat()) * (0.25f + 0.6f * mic.coerceIn(0f, 1f))
            radius to alpha
        }
    }

    /**
     * Loudness per slice of a chunk of 16-bit speech: [bins] values 0..1,
     * peak-ish (RMS ×2.5) so ordinary speech moves the surface visibly.
     */
    fun envelope(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset, bins: Int = 24): FloatArray {
        val out = FloatArray(bins)
        if (length <= 0) return out
        val per = max(1, length / bins)
        for (b in 0 until bins) {
            val start = offset + b * per
            if (start >= offset + length) break
            val end = minOf(start + per, offset + length)
            var sum = 0.0
            for (i in start until end) { val s = pcm[i] / 32768.0; sum += s * s }
            val rms = kotlin.math.sqrt(sum / (end - start)).toFloat()
            out[b] = (rms * 2.5f).coerceIn(0f, 1f)
        }
        return out
    }

    /** Eases a displayed waveform toward the latest one (fast attack, slower release), in place. */
    fun follow(shown: FloatArray, target: FloatArray, dt: Float) {
        for (i in shown.indices) {
            val goal = target.getOrElse(i) { 0f }
            val rate = if (goal > shown[i]) 18f else 5f
            shown[i] += (goal - shown[i]) * (rate * dt).coerceIn(0f, 1f)
        }
    }

    /** Positions (−1..1, inside the orb) and brightness of the synapse flashes shown while thinking. Deterministic per time slot. */
    fun synapses(time: Float, count: Int = 5): List<Triple<Float, Float, Float>> = (0 until count).map { k ->
        val slot = (time * 1.6f + k * 0.37f)
        val seed = (slot.toInt() * 7919 + k * 104729)
        val x = ((seed * 1103515245L + 12345L).ushr(16) % 1000).toFloat() / 1000f * 1.4f - 0.7f
        val y = ((seed * 22695477L + 1L).ushr(16) % 1000).toFloat() / 1000f * 1.4f - 0.7f
        val life = slot - slot.toInt()
        Triple(x, y, abs(sin(life * PI.toFloat())))
    }

    /** Frames per second the orb draws at: the thinking state stays light while the model needs the chip. */
    fun framesPerSecond(state: String, light: Boolean): Int = when {
        light -> 12
        state == "THINKING" || state == "RESEARCHING" || state == "SEARCHING" -> 20
        state == "IDLE" -> 24
        else -> 60
    }

    /** Smoothly closes a ring of points into a path: control points are the points themselves, segments end at midpoints. */
    fun ringPoints(radii: FloatArray, cx: Float, cy: Float, base: Float, rotation: Float = 0f): FloatArray {
        val n = radii.size
        val xy = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat() + rotation
            xy[2 * i] = cx + cos(a) * base * radii[i]
            xy[2 * i + 1] = cy + sin(a) * base * radii[i]
        }
        return xy
    }
}

/** Words for what ZED is doing right now, and the small result chips afterwards. */
object ActionLabels {
    private val PROGRESS = mapOf(
        "alarm.set" to "Setting alarm…", "timer.set" to "Starting timer…", "reminder.create" to "Setting reminder…",
        "reminder.reschedule" to "Moving reminder…", "reminder.cancel" to "Cancelling reminder…", "reminder.list" to "Checking reminders…",
        "calendar.create" to "Adding to calendar…", "calendar.move" to "Moving the event…", "calendar.today" to "Reading your calendar…", "todo.add" to "Adding task…",
        "todo.list" to "Reading your tasks…", "todo.complete" to "Ticking it off…", "memory.remember" to "Remembering…",
        "memory.forget" to "Forgetting…", "memory.list" to "Recalling…", "phone.call" to "Calling…", "sms.compose" to "Writing message…",
        "contacts.find" to "Looking up contact…", "app.open" to "Opening app…", "music.play" to "Starting music…", "media.control" to "Controlling playback…",
        "volume.set" to "Changing volume…", "flashlight.set" to "Flashlight…", "dnd.set" to "Do Not Disturb…", "maps.navigate" to "Starting navigation…",
        "weather.get" to "Checking the weather…", "web.research" to "Searching the web…", "web.open" to "Opening the web…",
        "notifications.recent" to "Reading notifications…", "device.status" to "Checking your phone…", "time.now" to "Checking the time…",
        "time.left" to "Counting down…", "routine.run" to "Running routine…", "home.control" to "Smart home…", "home.status" to "Checking smart home…",
        "home.secure" to "Smart home…", "docs.search" to "Searching documents…", "history.search" to "Searching history…", "file.read" to "Reading file…",
        "files.organize" to "Planning the tidy-up…", "camera.open" to "Opening camera…", "settings.panel" to "Opening settings…",
    )

    fun progress(tool: String): String = PROGRESS[tool] ?: (tool.substringBefore('.').replaceFirstChar { it.uppercase() } + "…")

    /** A short chip for a finished action ("Alarm 6:00", "Timer 5 min"), or null for actions that don't need one. */
    fun chip(tool: String, args: Map<String, String>): String? {
        fun clock(h: Int, m: Int) = "%d:%02d".format(h, m)
        fun time(iso: String?): String? = iso?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }?.let { t ->
            val h12 = if (t.hour % 12 == 0) 12 else t.hour % 12
            "%d:%02d %s".format(h12, t.minute, if (t.hour < 12) "AM" else "PM")
        }
        return when (tool) {
            "alarm.set" -> "Alarm " + clock(args["hour"]?.toIntOrNull() ?: 0, args["minute"]?.toIntOrNull() ?: 0)
            "timer.set" -> args["seconds"]?.toLongOrNull()?.let { s -> "Timer " + when { s % 3600 == 0L -> "${s / 3600} h"; s >= 60 && s % 60 == 0L -> "${s / 60} min"; s >= 60 -> "${s / 60} min ${s % 60} s"; else -> "$s s" } }
            "reminder.create", "reminder.reschedule" -> "Reminder " + (time(args["at"]) ?: "")
            "calendar.create" -> (args["title"] ?: "Event").take(18) + (time(args["start"])?.let { " $it" } ?: "")
            "calendar.move" -> "Moved " + args["query"].orEmpty().take(16)
            "todo.add" -> "Task added"
            "todo.complete" -> "Task done"
            "memory.remember" -> "Remembered"
            "phone.call" -> "Calling ${args["who"].orEmpty()}".trim()
            "sms.compose" -> "Message to ${args["to"].orEmpty()}".trim()
            "flashlight.set" -> "Flashlight " + if (args["on"] == "true") "on" else "off"
            "dnd.set" -> "Do Not Disturb " + if (args["on"] == "true") "on" else "off"
            "volume.set" -> "Volume " + (args["level"]?.let { "$it%" } ?: args["action"].orEmpty())
            "music.play" -> "Playing " + args["query"].orEmpty().take(16)
            "maps.navigate" -> "To " + args["destination"].orEmpty().take(18)
            "app.open" -> "Opened " + args["name"].orEmpty()
            "routine.run" -> args["name"].orEmpty().replaceFirstChar { it.uppercase() }.ifBlank { "Routine" } + " routine"
            "home.control" -> args["target"].orEmpty().take(18).replaceFirstChar { it.uppercase() }
            else -> null
        }?.trim()
    }

    /** Tools whose result is worth a card of its own in the floating assistant. */
    fun cardKind(tool: String): String? = when (tool) {
        "weather.get" -> "weather"
        "calendar.today", "time.left", "reminder.list" -> "schedule"
        "todo.list" -> "tasks"
        "web.research" -> "web"
        else -> null
    }
}
