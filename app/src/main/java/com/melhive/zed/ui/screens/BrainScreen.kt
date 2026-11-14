package com.melhive.zed.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.core.brain.BrainBuilder
import com.melhive.zed.core.brain.BrainEvent
import com.melhive.zed.core.brain.BrainGraph
import com.melhive.zed.core.brain.BrainLayout
import com.melhive.zed.core.brain.BrainNode
import com.melhive.zed.core.brain.BrainNodeType
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.MonoStyle
import com.melhive.zed.ui.theme.ZedColors
import com.melhive.zed.voice.ZedSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** True while the private brain is open: everything is drawn in black and white. */
@Volatile private var monochrome = false

private fun BrainNodeType.color(): Color = if (monochrome) when (this) {
    BrainNodeType.CORE -> Color.White
    BrainNodeType.CATEGORY -> Color(0xFFD0D0D0)
    BrainNodeType.CONCEPT -> Color(0xFF9A9A9A)
    else -> Color(0xFFECECEC)
} else when (this) {
    BrainNodeType.CORE -> ZedColors.Cyan
    BrainNodeType.CATEGORY -> ZedColors.Violet
    BrainNodeType.CONCEPT -> ZedColors.Amber
    BrainNodeType.FACT -> ZedColors.Teal
    BrainNodeType.TASK -> ZedColors.Green
    BrainNodeType.REMINDER -> ZedColors.Red
    BrainNodeType.DOCUMENT -> Color(0xFF6FA8FF)
    BrainNodeType.CONVERSATION -> Color(0xFFFF8AD8)
}

private val ITEM_TYPES = listOf(BrainNodeType.FACT, BrainNodeType.TASK, BrainNodeType.REMINDER, BrainNodeType.DOCUMENT, BrainNodeType.CONVERSATION)

/**
 * Turns the brain's 3D positions into screen positions: rotate around the
 * vertical axis (yaw), tilt (pitch), then perspective — near nodes come out
 * bigger, far ones smaller. The arrays are reused from frame to frame.
 */
private class BrainProjection {
    var sx = FloatArray(0); private set
    var sy = FloatArray(0); private set
    /** Perspective factor: above 1 = nearer than the core, below 1 = farther. */
    var persp = FloatArray(0); private set
    /** 0 = the far side of the brain, 1 = the near side. */
    var near = FloatArray(0); private set
    /** Node indices, farthest first — the order to draw in. */
    var order = IntArray(0); private set
    private var depth = FloatArray(0)

    fun project(
        x: FloatArray, y: FloatArray, z: FloatArray, radius: Float, yaw: Float, pitch: Float,
        px: Float, py: Float, pz: Float, scale: Float, cx: Float, cy: Float, spread: FloatArray? = null,
    ) {
        val n = x.size
        if (sx.size != n) {
            sx = FloatArray(n); sy = FloatArray(n); persp = FloatArray(n); near = FloatArray(n); depth = FloatArray(n)
            order = IntArray(n) { it }
        }
        val r = radius.coerceAtLeast(120f)
        val d = r * 2.8f
        val cosYaw = cos(yaw); val sinYaw = sin(yaw); val cosPitch = cos(pitch); val sinPitch = sin(pitch)
        for (i in 0 until n) {
            val k = if (spread != null && i < spread.size) spread[i] else 1f
            val dx = x[i] * k - px; val dy = y[i] * k - py; val dz = z[i] * k - pz
            val rx = dx * cosYaw + dz * sinYaw
            val z1 = -dx * sinYaw + dz * cosYaw
            val ry = dy * cosPitch - z1 * sinPitch
            val z2 = (dy * sinPitch + z1 * cosPitch).coerceAtLeast(-d * 0.75f)
            val p = d / (d + z2)
            sx[i] = cx + rx * scale * p; sy[i] = cy + ry * scale * p
            persp[i] = p; depth[i] = z2
            near[i] = (0.5f - z2 / (2f * r)).coerceIn(0f, 1f)
        }
        // Nearly sorted from the last frame, so an insertion sort is cheap.
        for (a in 1 until n) {
            val v = order[a]; val key = depth[v]
            var b = a - 1
            while (b >= 0 && depth[order[b]] < key) { order[b + 1] = order[b]; b-- }
            order[b + 1] = v
        }
    }
}

/** How much the connection between two nodes bows sideways (a fraction of its length; the same for both directions). */
private fun bend(lo: Int, hi: Int): Float = (((lo * 31 + hi * 17) % 7) - 3) / 3f * 0.13f

/** A point on the curved connection from A to B. */
private fun curve(ax: Float, ay: Float, bx: Float, by: Float, k: Float, t: Float): Offset {
    val cx = (ax + bx) / 2f - (by - ay) * k; val cy = (ay + by) / 2f + (bx - ax) * k
    val u = 1f - t
    return Offset(u * u * ax + 2f * u * t * cx + t * t * bx, u * u * ay + 2f * u * t * cy + t * t * by)
}

/** A curved connection that tapers from [widthA] to [widthB]. */
private fun DrawScope.synapse(ax: Float, ay: Float, bx: Float, by: Float, k: Float, color: Color, widthA: Float, widthB: Float, segments: Int) {
    if (segments <= 1) { drawLine(color, Offset(ax, ay), Offset(bx, by), strokeWidth = (widthA + widthB) / 2f, cap = StrokeCap.Round); return }
    var from = Offset(ax, ay)
    for (s in 1..segments) {
        val to = curve(ax, ay, bx, by, k, s / segments.toFloat())
        drawLine(color, from, to, strokeWidth = widthA + (widthB - widthA) * (s - 0.5f) / segments, cap = StrokeCap.Round)
        from = to
    }
}

private fun ease(v: Float): Float { val c = v.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

/** A signal ZED sent from the core to a memory it used. */
private class Pulse(val graph: BrainGraph, val path: IntArray, val start: Long)

private class Camera {
    var yaw = 0.6f
    var pitch = -0.28f
    var zoom = 1f
    var fit = 0f
    var px = 0f; var py = 0f; var pz = 0f
    var focusKey: String? = null
    var lastTouch = 0L
}

/** The time-lapse replay: the brain as it grew, one stored thing at a time. */
private class Lapse(val graph: BrainGraph, val times: LongArray) {
    var pos = 0f
    var index = -1
    var visible: BooleanArray = graph.visibleAt(times.first() - 1)
    var lastTick = 0L
}

private const val HOP_SECONDS = 0.28f
private const val BOOT_SECONDS = 2.6f

/**
 * Memory → Live Brain: everything ZED knows as a living, glowing hologram.
 * Areas of life surround the core, each stored item hangs off its area, and
 * concept nodes join items that mention the same thing. Memories ZED uses
 * often sit near the core and glow; unused ones fade outward. Drag to turn
 * it, pinch to fly in, tap a node to see what it holds.
 */
@Composable
fun BrainScreen(onBack: () -> Unit, onOpenChat: (Long) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val facts by c.memory.facts.collectAsStateWithLifecycle(initialValue = emptyList())
    val todos by c.todos.todos.collectAsStateWithLifecycle(initialValue = emptyList())
    val allItems by c.brain.items.collectAsStateWithLifecycle(initialValue = emptyList())
    val links by c.brain.links.collectAsStateWithLifecycle(initialValue = emptyList())
    val focusRequest by c.brainFocus.collectAsStateWithLifecycle()
    val recalls by c.brain.recalls.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    // Performance mode: "balanced" drops the travelling signals, "light" also the glow.
    val level = settings?.animationLevel ?: "full"
    val glow = (settings?.glow ?: true) && level != "light"
    val signals = level == "full"
    val sounds by rememberUpdatedState(settings?.brainSounds ?: true)

    var hidden by remember { mutableStateOf(emptySet<BrainNodeType>()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedKey by remember { mutableStateOf<String?>(null) }

    // Private Connections: null while locked. Unlocked notes exist only here, in this screen's memory.
    var privateNotes by remember { mutableStateOf<List<com.melhive.zed.security.PrivateStore.Note>?>(null) }
    var privateNote by remember { mutableStateOf("") }
    var privateMessage by remember { mutableStateOf<String?>(null) }
    val privateOpen = privateNotes != null
    val privateSpot = remember { FloatArray(3) }
    var showDisplay by remember { mutableStateOf(false) }
    val textFade = settings?.brainTextFade ?: 0.33f
    val nodeSize = settings?.brainNodeSize ?: 1f
    val linkWidth = settings?.brainLinkWidth ?: 1f
    val privateCount = remember(privateOpen) { c.privateStore.count }
    val tapNodeSize by rememberUpdatedState(nodeSize)

    fun lockPrivate() { privateNotes = null; privateNote = ""; selectedKey = null; monochrome = false }
    fun unlockPrivate() {
        val activity = context as? android.app.Activity ?: return
        if (!com.melhive.zed.security.DeviceAuth.available(context)) { privateMessage = "Set a screen lock (PIN, pattern or fingerprint) on this phone first. Private Connections are opened with it."; return }
        com.melhive.zed.security.DeviceAuth.confirm(activity, "Private Connections", "Unlock with your fingerprint or PIN") { ok ->
            if (ok) { selectedKey = null; searching = false; query = ""; monochrome = true; privateNotes = c.privateStore.load() }
        }
    }
    // Locked again when you leave the brain, when ZED goes to the background, and after three minutes untouched.
    DisposableEffect(Unit) { onDispose { monochrome = false } }
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) { onStopOrDispose { lockPrivate() } }
    // "Private mode" and "lock private mode" by voice.
    val privateAsk by c.brainPrivate.collectAsStateWithLifecycle()
    LaunchedEffect(privateAsk) {
        val ask = privateAsk ?: return@LaunchedEffect
        c.brainPrivate.value = null
        if (ask && !privateOpen) unlockPrivate() else if (!ask) lockPrivate()
    }

    val counts = remember(allItems) { allItems.groupingBy { it.type }.eachCount() }
    val items = remember(allItems, hidden, privateNotes) {
        privateNotes?.map { BrainItem(BrainNodeType.FACT, it.id, it.text, it.createdAt, strength = 0.7f) } ?: allItems.filter { it.type !in hidden }
    }

    var graph by remember { mutableStateOf(BrainGraph.EMPTY) }
    var layout by remember { mutableStateOf(BrainLayout()) }
    val births = remember { HashMap<String, Long>() }
    val flashes = remember { HashMap<String, Long>() }
    val pulses = remember { ArrayList<Pulse>() }
    val cam = remember { Camera() }
    val projection = remember { BrainProjection() }
    var frame by remember { mutableLongStateOf(0L) }
    var startFrame by remember { mutableLongStateOf(0L) }
    var bootStart by remember { mutableLongStateOf(0L) }
    var bootProgress by remember { mutableFloatStateOf(0f) }
    var lapse by remember { mutableStateOf<Lapse?>(null) }
    var lapseLabel by remember { mutableStateOf("") }
    var lapseProgress by remember { mutableFloatStateOf(0f) }

    fun tick(pitch: Int, haptic: Boolean = true) {
        if (!sounds) return
        ZedSounds.tick(context, pitch)
        if (haptic) runCatching { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }

    LaunchedEffect(items, links, privateOpen) {
        val next = withContext(Dispatchers.Default) { BrainBuilder.build(items, links = if (privateOpen) emptyList() else links) }
        if (layout.graph.nodes.isEmpty()) {
            // First time: unfold the brain off-screen so it appears already organized.
            layout = withContext(Dispatchers.Default) { BrainLayout().also { it.setGraph(next); it.settle() } }
        } else {
            val added = layout.setGraph(next)
            added.forEach { births[it] = frame }
            if (added.isNotEmpty() && lapse == null) tick(3, haptic = false)
        }
        graph = next
        lapse = null
        if (selectedKey != null && next.indexOf(selectedKey!!) < 0) selectedKey = null
    }

    val currentLayout by rememberUpdatedState(layout)
    val currentGraph by rememberUpdatedState(graph)
    val currentSelected by rememberUpdatedState(selectedKey)

    /** Sends a signal from the core to a node; it flashes when the signal arrives. */
    fun signal(key: String) {
        val g = currentGraph
        val i = g.indexOf(key)
        if (i <= 0 || frame == 0L) return
        val path = g.pathFromCore(i).toIntArray()
        if (path.size > 1) pulses += Pulse(g, path, frame)
        flashes[key] = frame + ((path.size - 1) * HOP_SECONDS * 1e9f).toLong()
    }

    // What ZED does elsewhere shows up here as it happens.
    LaunchedEffect(Unit) {
        c.brain.events.collect { e ->
            when (e) {
                is BrainEvent.Recalled -> if (!monochrome) { e.keys.forEach { signal(it) }; tick(1, haptic = false) }
                is BrainEvent.Saved -> Unit
            }
        }
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (startFrame == 0L) startFrame = now
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                last = now
                val l = currentLayout
                val g = currentGraph
                if (l.graph === g) l.step(dt)
                if (bootStart == 0L && g.nodes.isNotEmpty()) bootStart = now
                if (bootStart != 0L && bootProgress < 1f) bootProgress = ((now - bootStart) / 1e9f / BOOT_SECONDS).coerceIn(0f, 1f)

                // Camera: a slow turn unless the user is holding it; glide to whatever is in focus.
                val playing = lapse
                if (playing != null || (currentSelected == null && now - cam.lastTouch > 3_000_000_000L)) cam.yaw += dt * 0.13f
                var tx = 0f; var ty = 0f; var tz = 0f
                cam.focusKey?.let { key ->
                    val i = l.graph.indexOf(key)
                    if (i < 0) cam.focusKey = null else { tx = l.x[i]; ty = l.y[i]; tz = l.z[i] }
                }
                cam.px += (tx - cam.px) * 0.1f; cam.py += (ty - cam.py) * 0.1f; cam.pz += (tz - cam.pz) * 0.1f

                if (playing != null) {
                    if (playing.graph !== g) lapse = null else {
                        val seconds = (playing.times.size * 0.16f).coerceIn(4f, 14f)
                        playing.pos += dt * playing.times.size / seconds
                        val index = playing.pos.toInt().coerceAtMost(playing.times.size - 1)
                        if (index != playing.index) {
                            val next = g.visibleAt(playing.times[index])
                            var grew = false
                            for (i in next.indices) if (next[i] && !playing.visible[i]) { births[g.nodes[i].key] = now; grew = true }
                            playing.visible = next; playing.index = index
                            lapseLabel = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(playing.times[index]))
                            if (grew && now - playing.lastTick > 90_000_000L) { playing.lastTick = now; tick(index, haptic = false) }
                        }
                        lapseProgress = (playing.pos / playing.times.size).coerceIn(0f, 1f)
                        // Hold on the finished brain for a moment, then hand it back.
                        if (playing.pos > playing.times.size + playing.times.size / seconds * 1.5f) lapse = null
                    }
                }
                frame = now
            }
        }
    }

    val selected = selectedKey?.let { graph.indexOf(it) }?.takeIf { it >= 0 }
    val matches = remember(graph, query, searching) { if (searching && query.isNotBlank()) graph.search(query).toSet() else emptySet() }

    fun select(key: String?, center: Boolean) {
        selectedKey = key
        if (key != null) {
            signal(key)
            tick(key.hashCode())
            if (center) { cam.focusKey = key; if (cam.zoom < 1.8f) cam.zoom = 1.8f }
        }
    }

    // "Show me everything about Ana": fly to the best match and light up everything that mentions it.
    LaunchedEffect(focusRequest, graph) {
        val q = focusRequest ?: return@LaunchedEffect
        if (graph.nodes.isEmpty()) return@LaunchedEffect
        val hits = graph.find(q)
        searching = true; query = q.removePrefix("my ").trim()
        if (hits.isNotEmpty()) select(graph.nodes[hits.first()].key, center = true)
        c.brainFocus.value = null
    }

    fun startLapse() {
        val g = graph
        val times = g.nodes.filter { it.itemId != null && it.timestamp > 0 }.map { it.timestamp }.distinct().sorted().toLongArray()
        if (times.size < 2 || layout.graph !== g) return
        selectedKey = null; searching = false; query = ""
        cam.focusKey = null; cam.zoom = 1f
        lapseLabel = ""; lapseProgress = 0f
        lapse = Lapse(g, times)
    }

    privateMessage?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { privateMessage = null }, containerColor = ZedColors.SurfaceHigh,
            title = { Text("Private Connections") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { privateMessage = null }) { Text("OK") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            if (privateOpen) "Private brain" else "Live Brain", onBack,
            subtitle = if (privateOpen) "${privateNotes?.size ?: 0} private notes · locks when you leave" else if (graph.itemCount == 0) "Nothing stored yet" else "${graph.itemCount} things known · ${graph.edges.size} connections · ${graph.conceptCount} concepts",
            actions = {
                if (privateOpen) IconButton(onClick = { lockPrivate() }) { Icon(Icons.Outlined.Lock, "Lock and leave the private brain", tint = Color.White) }
                IconButton(onClick = { showDisplay = !showDisplay }) { Icon(Icons.Outlined.Tune, "Display", tint = if (showDisplay) ZedColors.Cyan else ZedColors.Text) }
                if (graph.itemCount >= 2 && !privateOpen) IconButton(onClick = { if (lapse != null) lapse = null else startLapse() }) {
                    Icon(if (lapse != null) Icons.Outlined.Stop else Icons.Outlined.History, if (lapse != null) "Stop the replay" else "Replay how the brain grew", tint = if (lapse != null) ZedColors.Amber else ZedColors.Text)
                }
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Outlined.Search, "Search the brain", tint = if (searching) ZedColors.Cyan else ZedColors.Text) }
                IconButton(onClick = { cam.focusKey = null; cam.zoom = 1f; cam.pitch = -0.28f; cam.lastTouch = 0L }) { Icon(Icons.Outlined.CenterFocusStrong, "Show the whole brain") }
            },
        )
        if (searching) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search what ZED knows") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                trailingIcon = { if (query.isNotEmpty()) Text("${matches.size}", color = ZedColors.Cyan, modifier = Modifier.padding(end = 12.dp)) },
            )
        }
        if (privateOpen) {
            fun addPrivate() {
                val text = privateNote.trim()
                if (text.isEmpty()) return
                val notes = (privateNotes ?: emptyList()) + com.melhive.zed.security.PrivateStore.Note(((privateNotes ?: emptyList()).maxOfOrNull { it.id } ?: 0L) + 1, text, System.currentTimeMillis())
                c.privateStore.save(notes); privateNotes = notes; privateNote = ""; cam.lastTouch = frame
            }
            OutlinedTextField(
                value = privateNote, onValueChange = { privateNote = it; cam.lastTouch = frame }, label = { Text("Add a private note") }, maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                trailingIcon = { if (privateNote.isNotBlank()) TextButton(onClick = { addPrivate() }) { Text("Add") } },
            )
        } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ITEM_TYPES.filter { (counts[it] ?: 0) > 0 }.forEach { type ->
                FilterChip(
                    selected = type !in hidden,
                    onClick = { hidden = if (type in hidden) hidden - type else hidden + type },
                    label = { Text("${type.plural} ${counts[type]}") },
                    leadingIcon = { Canvas(Modifier.size(8.dp)) { drawCircle(type.color()) } },
                )
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            val measurer = rememberTextMeasurer()
            val labels = remember(graph) { HashMap<String, TextLayoutResult>() }
            val labelStyle = TextStyle(fontSize = 11.sp, color = ZedColors.Text)
            val hubStyle = TextStyle(fontSize = 12.sp, color = ZedColors.Text, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            val spread = remember { arrayOf(FloatArray(0)) }

            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            cam.lastTouch = frame
                            cam.yaw -= pan.x * 0.006f
                            cam.pitch = (cam.pitch + pan.y * 0.006f).coerceIn(-1.25f, 1.25f)
                            cam.zoom = (cam.zoom * zoom).coerceIn(0.5f, 7f)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            val g = currentLayout.graph
                            if (cam.fit <= 0f || g !== currentGraph || projection.sx.size != g.nodes.size || lapse != null) return@detectTapGestures
                            cam.lastTouch = frame
                            // The locked private group beside the brain: tapping its centre asks for your fingerprint or PIN.
                            if (!monochrome && privateSpot[2] > 0f) {
                                val dx = tap.x - privateSpot[0]; val dy = tap.y - privateSpot[1]
                                if (sqrt(dx * dx + dy * dy) < privateSpot[2] + 16.dp.toPx()) { unlockPrivate(); return@detectTapGestures }
                            }
                            var best = -1; var bestD = Float.MAX_VALUE
                            for (i in g.nodes.indices) {
                                val dx = projection.sx[i] - tap.x; val dy = projection.sy[i] - tap.y
                                val d = sqrt(dx * dx + dy * dy)
                                val reach = nodeRadius(g.nodes[i], cam.fit * cam.zoom * projection.persp[i], density) * tapNodeSize + 18.dp.toPx()
                                // Of the nodes under the finger, the nearest to the viewer wins.
                                val rank = d - projection.near[i] * 12.dp.toPx()
                                if (d < reach && rank < bestD) { best = i; bestD = rank }
                            }
                            if (best >= 0) select(g.nodes[best].key, center = false) else selectedKey = null
                        }
                    },
            ) {
                val now = frame // read every frame: this is what keeps the brain moving
                val l = layout
                val g = l.graph
                if (g !== graph || g.nodes.isEmpty()) return@Canvas
                val t = (now - startFrame) / 1e9f
                val n = g.nodes.size
                val radius = l.radius()
                val fit = min(size.width, size.height) / 2f / (radius + 110f)
                cam.fit = if (cam.fit == 0f) fit else cam.fit + (fit.coerceIn(0.2f, 1.6f) - cam.fit) * 0.08f
                val scale = cam.fit * cam.zoom

                // Boot sequence: the core ignites, the areas unfold from it, then everything else grows out.
                val boot = if (bootStart == 0L) 0f else (now - bootStart) / 1e9f
                val booting = boot < BOOT_SECONDS
                if (spread[0].size != n) spread[0] = FloatArray(n) { 1f }
                val rev = spread[0]
                val vis = lapse?.takeIf { it.graph === g }?.visible
                for (i in 0 until n) {
                    val node = g.nodes[i]
                    var r = if (!booting) 1f else when (node.type) {
                        BrainNodeType.CORE -> ease(boot / 0.6f)
                        BrainNodeType.CATEGORY -> ease((boot - 0.45f) / 0.8f)
                        else -> ease((boot - 1.1f - (i % 12) * 0.04f) / 0.9f)
                    }
                    if (vis != null && !vis[i]) r = 0f
                    births[node.key]?.let { born ->
                        val age = (now - born) / 1e9f
                        if (age < 0.6f) r *= ease(age / 0.6f)
                    }
                    rev[i] = r
                }
                projection.project(l.x, l.y, l.z, radius, cam.yaw, cam.pitch, cam.px, cam.py, cam.pz, scale, size.width / 2f, size.height / 2f, rev)
                val sx = projection.sx; val sy = projection.sy; val near = projection.near; val persp = projection.persp

                val focusSet: Set<Int>? = when {
                    selected != null -> g.neighbors[selected].toSet() + selected
                    matches.isNotEmpty() -> matches
                    else -> null
                }
                val segments = when { !glow -> 1; g.edges.size > 420 -> 3; else -> 5 }
                val unit = 1.dp.toPx()

                // Connections, and the signals running along them.
                g.edges.forEachIndexed { e, edge ->
                    val lo = min(edge.a, edge.b); val hi = maxOf(edge.a, edge.b)
                    val grown = min(rev[lo], rev[hi])
                    if (grown <= 0.02f) return@forEachIndexed
                    val ax = sx[lo]; val ay = sy[lo]; val bx = sx[hi]; val by = sy[hi]
                    if ((ax < -60f && bx < -60f) || (ay < -60f && by < -60f) || (ax > size.width + 60f && bx > size.width + 60f) || (ay > size.height + 60f && by > size.height + 60f)) return@forEachIndexed
                    val hot = selected != null && (edge.a == selected || edge.b == selected)
                    val dim = focusSet != null && !hot
                    val color = g.nodes[hi].type.color()
                    val depthA = 0.3f + 0.7f * near[lo]; val depthB = 0.3f + 0.7f * near[hi]
                    val depth = (depthA + depthB) / 2f
                    val k = bend(lo, hi)
                    val alpha = (when { hot -> 0.9f; dim -> 0.06f; else -> (0.16f + 0.16f * edge.strength) * depth }) * grown
                    // Thick where it leaves the hub, thin where it reaches the memory.
                    val wide = (if (hot) 2.2f else 1.5f) * unit * persp[lo].coerceIn(0.7f, 1.5f) * linkWidth
                    val thin = (if (hot) 1.2f else 0.7f) * unit * persp[hi].coerceIn(0.7f, 1.5f) * linkWidth
                    synapse(ax, ay, bx, by, k, color.copy(alpha = alpha.coerceIn(0f, 1f)), wide, thin, segments)
                    // Named relationships ("sister", "works at") are written along the connection when it is in focus or close up.
                    edge.label?.let { lbl ->
                        if (hot || (focusSet == null && scale * (persp[lo] + persp[hi]) / 2f > 1.1f)) {
                            val text = labels.getOrPut("rel:$lbl") { measurer.measure(lbl, TextStyle(fontSize = 10.sp, color = ZedColors.Amber), maxLines = 1) }
                            val mid = curve(ax, ay, bx, by, k, 0.5f)
                            drawText(text, color = ZedColors.Amber.copy(alpha = if (hot) 0.95f else 0.6f * depth), topLeft = Offset(mid.x - text.size.width / 2f, mid.y - text.size.height - 2f * unit))
                        }
                    }

                    if (((!glow || !signals) && !hot) || dim || grown < 0.9f) return@forEachIndexed
                    val period = if (hot) 0.9f else 2.2f + (e * 37 % 17) / 10f
                    val u = t / period + e * 0.618f
                    val cycle = floor(u).toInt()
                    if (!hot && (cycle * 7 + e * 13) % 2 != 0) return@forEachIndexed
                    val f = u - cycle
                    // Selected: signals leave the selected node. Otherwise the direction alternates.
                    val forward = if (hot) lo == selected else (cycle + e) % 2 == 0
                    val at = if (forward) f else 1f - f
                    val fade = sin(PI.toFloat() * f) * depth
                    val p = curve(ax, ay, bx, by, k, at)
                    // A short trail behind the signal.
                    for (s in 1..4) {
                        val back = (if (forward) at - s * 0.04f else at + s * 0.04f).coerceIn(0f, 1f)
                        drawCircle(color.copy(alpha = 0.4f * fade * (1f - s / 5f)), (2.2f - s * 0.35f) * unit, curve(ax, ay, bx, by, k, back))
                    }
                    drawCircle(color.copy(alpha = 0.18f * fade), 6f * unit, p)
                    drawCircle(color.copy(alpha = 0.5f * fade), 2.8f * unit, p)
                    drawCircle(Color.White.copy(alpha = (0.95f * fade).coerceIn(0f, 1f)), 1.3f * unit, p)
                    // A spark where it lands.
                    if (f > 0.86f) {
                        val end = if (forward) Offset(bx, by) else Offset(ax, ay)
                        val burst = (f - 0.86f) / 0.14f
                        for (s in 0 until 5) {
                            val angle = s * 1.2566f + e
                            val from = end + Offset(cos(angle), sin(angle)) * (4f + 6f * burst) * unit
                            val to = end + Offset(cos(angle), sin(angle)) * (6f + 10f * burst) * unit
                            drawLine(Color.White.copy(alpha = (1f - burst) * 0.65f * depth), from, to, strokeWidth = 0.9f * unit, cap = StrokeCap.Round)
                        }
                    }
                }

                // Signals ZED sent from the core to memories it used.
                val iterator = pulses.iterator()
                while (iterator.hasNext()) {
                    val pulse = iterator.next()
                    val hops = pulse.path.size - 1
                    val at = (now - pulse.start) / 1e9f / HOP_SECONDS
                    if (pulse.graph !== g || at >= hops || at < 0f) { iterator.remove(); continue }
                    val hop = at.toInt()
                    val a = pulse.path[hop]; val b = pulse.path[hop + 1]
                    val lo = min(a, b); val hi = maxOf(a, b)
                    val f = at - hop
                    val p = curve(sx[lo], sy[lo], sx[hi], sy[hi], bend(lo, hi), if (a == lo) f else 1f - f)
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f), ZedColors.Cyan.copy(alpha = 0.3f), Color.Transparent), p, 11f * unit), 11f * unit, p)
                    drawCircle(Color.White, 2.2f * unit, p)
                }

                // Neurons, far ones first so near ones are drawn over them.
                val order = projection.order
                for (slot in 0 until n) {
                    val i = order[slot]
                    val grown = rev[i]
                    if (grown <= 0.02f) continue
                    val node = g.nodes[i]
                    val p = Offset(sx[i], sy[i])
                    if (p.x < -80f || p.y < -80f || p.x > size.width + 80f || p.y > size.height + 80f) continue
                    val color = node.type.color()
                    val isSelected = i == selected
                    val lit = focusSet == null || i in focusSet
                    val depth = 0.32f + 0.68f * near[i]
                    var alpha = (if (lit) 0.9f else 0.18f) * depth
                    // Memories ZED hasn't touched in a long time fade; they are never removed.
                    if (node.itemId != null) alpha *= 0.4f + 0.6f * node.strength
                    if (node.done) alpha *= 0.6f
                    if (isSelected) alpha = 1f
                    var r = nodeRadius(node, scale * persp[i], density) * grown * nodeSize

                    // New memories grow in with a ripple.
                    births[node.key]?.let { born ->
                        val age = (now - born) / 1e9f
                        if (age < 1.2f) {
                            r *= 1f + 0.35f * sin(PI.toFloat() * (age / 0.6f).coerceIn(0f, 1f))
                            drawCircle(color.copy(alpha = (1f - age / 1.2f) * 0.5f), r + age * 40f * unit, p, style = Stroke(unit))
                        }
                    }
                    // A memory ZED just used lights up.
                    var flash = 0f
                    flashes[node.key]?.let { at ->
                        val age = (now - at) / 1e9f
                        if (age in 0f..1.8f) {
                            flash = 1f - age / 1.8f
                            drawCircle(Color.White.copy(alpha = flash * 0.6f), r + age * 30f * unit, p, style = Stroke(unit))
                            val bloom = r * (3f + 4f * flash)
                            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * flash), color.copy(alpha = 0.3f * flash), Color.Transparent), p, bloom), bloom, p)
                        }
                    }
                    val breathe = 1f + 0.05f * sin(t * 1.6f + i * 0.9f)

                    if (node.type == BrainNodeType.CORE) drawCore(p, r, color, t, glow)
                    else {
                        // Glow is kept for what matters: the areas, strong memories, and whatever is selected.
                        val haloAlpha = when {
                            isSelected -> 0.3f
                            node.type == BrainNodeType.CATEGORY -> 0.24f * alpha
                            node.type == BrainNodeType.CONCEPT -> 0.12f * alpha
                            node.itemId != null && node.strength >= 0.6f -> 0.18f * alpha
                            else -> 0f
                        }
                        if (glow && haloAlpha > 0f) {
                            val halo = r * 2.6f * breathe
                            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = haloAlpha.coerceIn(0f, 1f)), Color.Transparent), p, halo), halo, p)
                        }
                        drawCircle(color.copy(alpha = alpha.coerceIn(0f, 1f)), r, p)
                        // A small nucleus, only on the near side, so depth reads at a glance.
                        val nucleus = ((0.2f + 0.45f * near[i]) * alpha + flash * 0.5f).coerceIn(0f, 1f)
                        if (nucleus > 0.04f) drawCircle(Color.White.copy(alpha = nucleus), r * 0.4f, p)
                        if (node.pinned) drawCircle(Color.White.copy(alpha = (0.5f * alpha).coerceIn(0f, 1f)), r + 1.8f * unit, p, style = Stroke(0.8f * unit))
                    }
                    if (i in matches) drawCircle(ZedColors.Amber, r + 3.5f * unit, p, style = Stroke(1.2f * unit))
                    if (isSelected) drawCircle(Color.White.copy(alpha = 0.85f), r + 4f * unit + 1f * unit * sin(t * 4f), p, style = Stroke(1.2f * unit))

                    val hub = node.type == BrainNodeType.CORE || node.type == BrainNodeType.CATEGORY
                    val apparent = scale * persp[i]
                    // Display → Text fade threshold: how far you must zoom in before names appear.
                    val nameAt = 0.35f + 2.75f * textFade
                    val threshold = when { hub -> if (textFade > 0.6f) nameAt * 0.35f else 0f; node.type == BrainNodeType.CONCEPT -> nameAt * 0.48f; else -> nameAt }
                    val fadeIn = if (threshold <= 0f || isSelected || focusSet != null) 1f else ((apparent - threshold) / (0.25f * threshold)).coerceIn(0f, 1f)
                    val show = grown > 0.9f && when {
                        !lit -> false
                        isSelected -> true
                        hub -> (near[i] > 0.2f && fadeIn > 0f) || focusSet != null
                        node.type == BrainNodeType.CONCEPT -> (fadeIn > 0f && near[i] > 0.45f) || focusSet != null
                        else -> (fadeIn > 0f && near[i] > 0.5f) || focusSet != null
                    }
                    if (show) {
                        val text = labels.getOrPut(node.key) {
                            measurer.measure(if (hub) node.label.uppercase() else node.label, if (hub) hubStyle else labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1)
                        }
                        drawText(text, color = ZedColors.Text.copy(alpha = fadeIn * if (hub || isSelected) (0.45f + 0.55f * near[i]) else (0.35f + 0.5f * near[i])), topLeft = Offset(p.x - text.size.width / 2f, p.y + r + 3f * unit))
                    }
                }

                // Private Connections: a separate black-and-white group that turns with the brain but is joined to nothing.
                if (!monochrome && !booting && lapse == null) {
                    val a = 0.9f - cam.yaw
                    val reachOut = (radius + 95f) * scale
                    val px = sx[0] + cos(a) * reachOut
                    val py = sy[0] + sin(a) * reachOut * 0.45f + reachOut * 0.25f
                    val depth = 0.55f + 0.45f * ((sin(a) + 1f) / 2f)
                    val r = 9.5f * unit * sqrt(scale.coerceIn(0.3f, 3f)) * nodeSize
                    privateSpot[0] = px; privateSpot[1] = py; privateSpot[2] = r
                    // Its notes circle it as plain grey points: how many there are, nothing about what they hold.
                    val dots = min(privateCount, 16)
                    for (k in 0 until dots) {
                        val ang = t * 0.25f + k * (2f * PI.toFloat() / dots.coerceAtLeast(1))
                        val orbit = r * (2.3f + 0.5f * sin(k * 1.7f))
                        drawCircle(Color.White.copy(alpha = 0.32f * depth), 1.7f * unit * nodeSize, Offset(px + cos(ang) * orbit, py + sin(ang) * orbit * 0.6f))
                    }
                    drawCircle(Color.Black, r, Offset(px, py))
                    drawCircle(Color.White.copy(alpha = 0.85f * depth), r, Offset(px, py), style = Stroke(1.3f * unit))
                    // A small padlock in the middle.
                    val w = r * 0.8f; val h = r * 0.6f
                    drawRect(Color.White.copy(alpha = 0.85f * depth), Offset(px - w / 2f, py - h * 0.25f), androidx.compose.ui.geometry.Size(w, h))
                    drawArc(Color.White.copy(alpha = 0.85f * depth), 180f, 180f, false, Offset(px - w * 0.3f, py - h * 0.85f), androidx.compose.ui.geometry.Size(w * 0.6f, h * 1.2f), style = Stroke(1.3f * unit))
                    if (scale > 0.5f) {
                        val text = labels.getOrPut("private-group") { measurer.measure("PRIVATE", hubStyle, maxLines = 1) }
                        drawText(text, color = Color.White.copy(alpha = 0.6f * depth), topLeft = Offset(px - text.size.width / 2f, py + r + 4f * unit))
                    }
                } else privateSpot[2] = 0f
            }

            // Display options, like a graph view's: when names appear, how big the points are, how thick the links.
            if (showDisplay) ZedCard(Modifier.align(Alignment.TopEnd).padding(10.dp).widthIn(max = 280.dp)) {
                var fade by remember(textFade) { mutableFloatStateOf(textFade) }
                var size by remember(nodeSize) { mutableFloatStateOf(nodeSize) }
                var link by remember(linkWidth) { mutableFloatStateOf(linkWidth) }
                fun save() { scope.launch { c.settings.setBrainDisplay(fade, size, link) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DISPLAY", style = MaterialTheme.typography.labelSmall, color = ZedColors.Cyan, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showDisplay = false }) { Icon(Icons.Outlined.Close, "Close display options", tint = ZedColors.Muted) }
                }
                Text("Text fade threshold", style = MaterialTheme.typography.labelMedium)
                Text(if (fade < 0.05f) "Names always show" else if (fade > 0.6f) "Names only when zoomed in close" else "Names appear as you zoom in", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                androidx.compose.material3.Slider(value = fade, onValueChange = { fade = it }, onValueChangeFinished = { save() }, valueRange = 0f..1f)
                Text("Node size", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(value = size, onValueChange = { size = it }, onValueChangeFinished = { save() }, valueRange = 0.5f..2.5f)
                Text("Link thickness", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(value = link, onValueChange = { link = it }, onValueChangeFinished = { save() }, valueRange = 0.4f..3f)
                TextButton(onClick = { scope.launch { c.settings.setBrainDisplay(0.33f, 1f, 1f) } }) { Text("Reset to default") }
            }

            // HUD readout.
            if (graph.itemCount > 0) Column(Modifier.align(Alignment.TopStart).padding(horizontal = 14.dp, vertical = 8.dp)) {
                val shown = ease(bootProgress)
                Text("NEURONS  %03d".format((graph.nodes.size * shown).toInt()), style = MonoStyle, color = ZedColors.Cyan.copy(alpha = 0.8f))
                Text("SYNAPSES %03d".format((graph.edges.size * shown).toInt()), style = MonoStyle, color = ZedColors.Cyan.copy(alpha = 0.8f))
                if (recalls > 0) Text("RECALLS  %03d".format(recalls), style = MonoStyle, color = ZedColors.Muted)
                if (bootProgress < 1f) Text(if (bootProgress < 0.25f) "IGNITING CORE" else if (bootProgress < 0.6f) "UNFOLDING AREAS" else "LINKING MEMORIES", style = MonoStyle, color = ZedColors.Amber)
            }

            if (lapse != null) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TIME-LAPSE", style = MaterialTheme.typography.labelSmall, color = ZedColors.Amber, letterSpacing = 3.sp)
                    Text(lapseLabel, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { lapseProgress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = ZedColors.Amber, trackColor = ZedColors.Outline)
                }
            } else if (graph.itemCount == 0) {
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (allItems.isEmpty()) "ZED's brain is waiting for its first memory" else "Everything is filtered out", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (allItems.isEmpty()) "Say “remember that my sister's name is Ana”, add a task or set a reminder — and watch it appear here, connected to what ZED already knows."
                        else "Turn a type back on above to see it.",
                        style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else if (selected != null && privateOpen) {
                // Inside the private brain: the note itself, what it connects to, and a way to delete it.
                val node = graph.nodes[selected]
                ZedCard(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), accent = Color.White) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (node.type == BrainNodeType.FACT) "Private note" else if (node.type == BrainNodeType.CATEGORY) "Area" else node.type.label, Color.White)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { selectedKey = null }) { Icon(Icons.Outlined.Close, "Close", tint = ZedColors.Muted) }
                    }
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text(if (node.type == BrainNodeType.CATEGORY) node.label else node.text, style = MaterialTheme.typography.bodyLarge)
                        val id = node.itemId
                        if (id != null && node.type == BrainNodeType.FACT) TextButton(onClick = {
                            val notes = (privateNotes ?: emptyList()).filter { it.id != id }
                            c.privateStore.save(notes); privateNotes = notes; selectedKey = null
                        }) { Text("Delete this note", color = ZedColors.Red) }
                        val linked = graph.neighbors[selected].map { graph.nodes[it] }.filter { it.type != BrainNodeType.CORE }
                        if (linked.isNotEmpty()) {
                            Text("CONNECTED TO ${linked.size}", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                            linked.take(24).forEach { other ->
                                Text(other.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clickable { select(other.key, center = true) }.padding(vertical = 5.dp))
                            }
                        }
                    }
                }
            } else if (selected != null) {
                val node = graph.nodes[selected]
                ZedCard(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), accent = node.type.color()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(if (node.type == BrainNodeType.CATEGORY) "Area" else node.type.label, node.type.color())
                        if (node.category != null && node.type != BrainNodeType.CATEGORY) {
                            Spacer(Modifier.width(8.dp))
                            Text(node.category!!, style = MaterialTheme.typography.labelMedium, color = ZedColors.Muted)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { selectedKey = null }) { Icon(Icons.Outlined.Close, "Close", tint = ZedColors.Muted) }
                    }
                    Column(Modifier.heightIn(max = if (node.type == BrainNodeType.CONVERSATION || node.type == BrainNodeType.DOCUMENT) 340.dp else 230.dp).verticalScroll(rememberScrollState())) {
                        Text(if (node.type == BrainNodeType.CATEGORY) node.label else node.text, style = MaterialTheme.typography.bodyLarge)
                        if (node.type == BrainNodeType.CATEGORY || node.type == BrainNodeType.CONCEPT)
                            Text(node.text, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                        // What is inside: the messages of a conversation, the text of a document.
                        val inside = node.itemId
                        if (inside != null && (node.type == BrainNodeType.CONVERSATION || node.type == BrainNodeType.DOCUMENT)) {
                            val content by androidx.compose.runtime.produceState<List<Pair<String, String>>?>(null, node.key) {
                                value = runCatching {
                                    if (node.type == BrainNodeType.CONVERSATION)
                                        c.conversations.allMessages(inside).filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
                                            .takeLast(60).map { (if (it.role == "user") "You" else "ZED") to it.content.take(1200) }
                                    else c.db.knowledge().chunks(inside).take(10).map { "" to it.text }
                                }.getOrDefault(emptyList())
                            }
                            content?.let { parts ->
                                if (parts.isEmpty()) Text(if (node.type == BrainNodeType.CONVERSATION) "Nothing was said in this conversation." else "No text was read from this document.", style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                                parts.forEach { (who, text) ->
                                    Text(
                                        if (who.isEmpty()) text else "$who  $text",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (who == "ZED") ZedColors.Cyan.copy(alpha = 0.9f) else ZedColors.Text,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                        if (node.itemId != null && node.timestamp > 0)
                            Text(formatTime(node.timestamp) + if (node.done) " · done" else "", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 2.dp))
                        if (node.type == BrainNodeType.FACT) {
                            val fact = facts.firstOrNull { it.id == node.itemId }
                            val strength = when { node.strength >= 0.75f -> "Strong"; node.strength >= 0.45f -> "Steady"; else -> "Fading" }
                            Text(
                                "$strength memory · " + when (val uses = fact?.uses ?: 0) { 0 -> "not used yet"; 1 -> "used once"; else -> "used $uses times" } +
                                    (fact?.lastUsedAt?.takeIf { it > 0 }?.let { ", last on ${formatTime(it)}" } ?: ""),
                                style = MaterialTheme.typography.labelSmall, color = if (node.strength >= 0.45f) ZedColors.Teal else ZedColors.Muted,
                            )
                        }

                        val id = node.itemId
                        if (id != null) Row {
                            when (node.type) {
                                BrainNodeType.FACT -> {
                                    TextButton(onClick = { scope.launch { facts.firstOrNull { it.id == id }?.let { c.memory.update(it.copy(pinned = !it.pinned)) } } }) { Text(if (node.pinned) "Unpin" else "Pin") }
                                    TextButton(onClick = { selectedKey = null; scope.launch { c.memory.delete(listOf(id)) } }) { Text("Forget", color = ZedColors.Red) }
                                }
                                BrainNodeType.TASK -> {
                                    TextButton(onClick = { scope.launch { todos.firstOrNull { it.id == id }?.let { c.todos.setDone(it, !it.done) } } }) { Text(if (node.done) "Mark not done" else "Mark done") }
                                    TextButton(onClick = { selectedKey = null; scope.launch { c.todos.delete(id) } }) { Text("Delete", color = ZedColors.Red) }
                                }
                                BrainNodeType.REMINDER -> TextButton(onClick = { selectedKey = null; scope.launch { c.reminderScheduler.cancelReminder(id) } }) { Text("Cancel reminder", color = ZedColors.Red) }
                                BrainNodeType.CONVERSATION -> TextButton(onClick = { onOpenChat(id) }) { Text("Open conversation") }
                                else -> Unit
                            }
                        }

                        val linked = graph.neighbors[selected].map { graph.nodes[it] }.filter { it.type != BrainNodeType.CORE }
                            .sortedBy { it.type.ordinal }
                        if (linked.isNotEmpty()) {
                            Text("CONNECTED TO ${linked.size}", style = MaterialTheme.typography.labelSmall, color = ZedColors.Cyan, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                            linked.take(24).forEach { other ->
                                Row(Modifier.fillMaxWidth().clickable { select(other.key, center = true) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Canvas(Modifier.size(8.dp)) { drawCircle(other.type.color()) }
                                    Spacer(Modifier.width(8.dp))
                                    Text(other.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    val rel = graph.edgeLabel(selected, graph.indexOf(other.key))
                                    Text(rel ?: if (other.type == BrainNodeType.CATEGORY) "Area" else other.type.label, style = MaterialTheme.typography.labelSmall, color = if (rel != null) ZedColors.Amber else ZedColors.Muted)
                                }
                            }
                        }
                    }
                }
            } else if (matches.isNotEmpty()) {
                // Search results: tap one to fly to it.
                ZedCard(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), accent = ZedColors.Amber) {
                    Column(Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
                        matches.take(20).forEach { i ->
                            val n = graph.nodes.getOrNull(i) ?: return@forEach
                            Row(Modifier.fillMaxWidth().clickable { select(n.key, center = true) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Canvas(Modifier.size(8.dp)) { drawCircle(n.type.color()) }
                                Spacer(Modifier.width(8.dp))
                                Text(n.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(n.type.label, style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Neurons are points of light, not balloons: a clear size order (core, areas,
 * concepts, memories by strength) and only gentle growth when zooming in —
 * flying closer spreads them apart rather than inflating them.
 */
private fun nodeRadius(node: BrainNode, apparentScale: Float, density: Float): Float {
    val dp = when (node.type) {
        BrainNodeType.CORE -> 11f
        BrainNodeType.CATEGORY -> 5.5f + 3f * node.weight
        BrainNodeType.CONCEPT -> 4f
        else -> 3f + 3f * node.strength + if (node.pinned) 0.5f else 0f
    }
    return dp * density * sqrt(apparentScale.coerceAtLeast(0.01f)).coerceIn(0.75f, 1.8f)
}

/**
 * The brain in miniature, for Home, LIVE and the floating assistant: the same
 * memories in the same places, slowly turning. A memory flashes when ZED uses
 * it and a new one blooms when ZED saves it. Drawn at half the frame rate so
 * it costs next to nothing beside the orb and the model.
 */
@Composable
fun MiniBrain(modifier: Modifier = Modifier, glow: Boolean = true) {
    val c = rememberContainer()
    val snapshot by c.brain.snapshot.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    // Performance mode: full 20 fps, balanced 12 fps, light one frame a second (a small view doesn't need more).
    val frameGap = when (settings?.animationLevel) { "light" -> 1_000_000_000L; "balanced" -> 80_000_000L; else -> 50_000_000L }
    val gap by rememberUpdatedState(frameGap)
    val glowOn = glow && settings?.animationLevel != "light"
    val projection = remember { BrainProjection() }
    val flashes = remember { HashMap<String, Long>() }
    var frame by remember { mutableLongStateOf(0L) }
    var startFrame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) withFrameNanos { now ->
            if (startFrame == 0L) startFrame = now
            if (now - frame >= gap) frame = now
        }
    }
    LaunchedEffect(Unit) {
        c.brain.events.collect { e ->
            when (e) {
                is BrainEvent.Recalled -> e.keys.forEach { flashes[it] = frame }
                is BrainEvent.Saved -> flashes[e.key] = frame
            }
        }
    }

    Canvas(modifier) {
        val now = frame
        val t = (now - startFrame) / 1e9f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val unit = 1.dp.toPx()
        val snap = snapshot
        if (snap == null || snap.graph.nodes.size <= 1) {
            // Nothing stored yet: just the core, waiting.
            val r = min(size.width, size.height) * 0.09f * (1f + 0.1f * sin(t * 2f))
            if (glowOn) drawCircle(Brush.radialGradient(listOf(ZedColors.Cyan.copy(alpha = 0.4f), Color.Transparent), centre, r * 4f), r * 4f, centre)
            drawCircle(Brush.radialGradient(listOf(Color.White, ZedColors.Cyan), centre, r), r, centre)
            return@Canvas
        }
        val g = snap.graph
        val scale = min(size.width, size.height) / 2f / (snap.radius + 60f)
        projection.project(snap.x, snap.y, snap.z, snap.radius, t * 0.22f, -0.3f, 0f, 0f, 0f, scale, centre.x, centre.y)
        val sx = projection.sx; val sy = projection.sy; val near = projection.near; val persp = projection.persp
        // Which memories are lit right now, and how brightly.
        val lit = FloatArray(g.nodes.size)
        if (flashes.isNotEmpty()) {
            val done = flashes.entries.iterator()
            while (done.hasNext()) {
                val entry = done.next()
                val age = (now - entry.value) / 1e9f
                if (age > 2.2f) { done.remove(); continue }
                val i = g.indexOf(entry.key)
                if (i >= 0) lit[i] = (1f - age / 2.2f).coerceIn(0f, 1f)
            }
        }
        g.edges.forEach { edge ->
            val depth = 0.25f + 0.75f * (near[edge.a] + near[edge.b]) / 2f
            val hot = maxOf(lit[edge.a], lit[edge.b])
            val color = if (hot > 0f) Color.White else g.nodes[edge.b].type.color()
            drawLine(color.copy(alpha = ((0.16f + 0.12f * edge.strength) * depth + 0.5f * hot).coerceIn(0f, 1f)), Offset(sx[edge.a], sy[edge.a]), Offset(sx[edge.b], sy[edge.b]), strokeWidth = (0.8f + 0.6f * hot) * unit)
        }
        val order = projection.order
        for (slot in order.indices) {
            val i = order[slot]
            val node = g.nodes[i]
            val p = Offset(sx[i], sy[i])
            val color = node.type.color()
            val depth = 0.3f + 0.7f * near[i]
            val core = node.type == BrainNodeType.CORE
            val r = (if (core) 3.8f else if (node.type == BrainNodeType.CATEGORY) 2.5f else 1.2f + 1.6f * node.strength) * unit * persp[i].coerceIn(0.7f, 1.3f) * (1f + 0.05f * sin(t * 1.7f + i))
            val alpha = 0.85f * if (node.itemId != null) depth * (0.4f + 0.6f * node.strength) else depth
            if (glowOn && (core || node.type == BrainNodeType.CATEGORY)) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = (if (core) 0.3f else 0.15f) * alpha), Color.Transparent), p, r * 3f), r * 3f, p)
            if (lit[i] > 0f) {
                val bloom = r * (3f + 5f * lit[i])
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f * lit[i]), color.copy(alpha = 0.4f * lit[i]), Color.Transparent), p, bloom), bloom, p)
            }
            drawCircle(if (core) Color.White.copy(alpha = 0.9f) else color.copy(alpha = alpha.coerceIn(0f, 1f)), r, p)
        }
    }
}

/** The core: a bright reactor with two counter-rotating arcs. */
private fun DrawScope.drawCore(p: Offset, r: Float, color: Color, t: Float, glow: Boolean) {
    val pulse = 1f + 0.08f * sin(t * 2.2f)
    if (glow) {
        val halo = r * 3f * pulse
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.26f), ZedColors.Violet.copy(alpha = 0.07f), Color.Transparent), p, halo), halo, p)
    }
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), color.copy(alpha = 0.85f), color.copy(alpha = 0.4f)), p, r), r, p)
    val ring = r * 1.45f
    drawArc(color.copy(alpha = 0.6f), t * 50f % 360f, 100f, false, Offset(p.x - ring, p.y - ring), Size(ring * 2, ring * 2), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
    drawArc(color.copy(alpha = 0.6f), t * 50f % 360f + 180f, 100f, false, Offset(p.x - ring, p.y - ring), Size(ring * 2, ring * 2), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
    val outer = r * 1.9f
    drawArc(ZedColors.Violet.copy(alpha = 0.5f), 360f - t * 32f % 360f, 60f, false, Offset(p.x - outer, p.y - outer), Size(outer * 2, outer * 2), style = Stroke(0.8.dp.toPx(), cap = StrokeCap.Round))
    drawArc(ZedColors.Violet.copy(alpha = 0.5f), 360f - t * 32f % 360f + 180f, 60f, false, Offset(p.x - outer, p.y - outer), Size(outer * 2, outer * 2), style = Stroke(0.8.dp.toPx(), cap = StrokeCap.Round))
}

/** A small living network, used on the card that opens the Live Brain. */
@Composable
fun BrainGlyph(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "brain")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val pts = listOf(0.5f to 0.5f, 0.18f to 0.25f, 0.82f to 0.2f, 0.88f to 0.68f, 0.5f to 0.92f, 0.12f to 0.72f, 0.5f to 0.1f)
            .map { Offset(it.first * size.width, it.second * size.height) }
        val colors = listOf(ZedColors.Cyan, ZedColors.Violet, ZedColors.Teal, ZedColors.Amber, ZedColors.Green, Color(0xFFFF8AD8), Color(0xFF6FA8FF))
        val links = listOf(0 to 1, 0 to 2, 0 to 3, 0 to 4, 0 to 5, 0 to 6, 1 to 6, 2 to 3, 4 to 5)
        links.forEachIndexed { e, (a, b) ->
            drawLine(colors[b].copy(alpha = 0.35f), pts[a], pts[b], strokeWidth = 1.dp.toPx())
            val f = (phase * 2f + e * 0.37f) % 1f
            val p = pts[a] + (pts[b] - pts[a]) * f
            drawCircle(colors[b].copy(alpha = 0.35f * sin(PI.toFloat() * f)), 3.dp.toPx(), p)
            drawCircle(Color.White.copy(alpha = sin(PI.toFloat() * f)), 1.2.dp.toPx(), p)
        }
        pts.forEachIndexed { i, p ->
            val r = (if (i == 0) 5.5f else 3.2f).dp.toPx() * (1f + 0.12f * sin(phase * 2f * PI.toFloat() + i))
            drawCircle(colors[i].copy(alpha = 0.22f), r * 2.2f, p)
            drawCircle(colors[i], r, p)
        }
    }
}
