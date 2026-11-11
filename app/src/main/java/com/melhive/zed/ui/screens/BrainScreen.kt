package com.melhive.zed.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.zed.core.brain.BrainBuilder
import com.melhive.zed.core.brain.BrainGraph
import com.melhive.zed.core.brain.BrainItem
import com.melhive.zed.core.brain.BrainLayout
import com.melhive.zed.core.brain.BrainNode
import com.melhive.zed.core.brain.BrainNodeType
import com.melhive.zed.ui.components.StatusPill
import com.melhive.zed.ui.components.ZedCard
import com.melhive.zed.ui.navigation.rememberContainer
import com.melhive.zed.ui.theme.ZedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private fun BrainNodeType.color(): Color = when (this) {
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
 * Memory → Live Brain: everything ZED knows as a living, glowing network.
 * Areas of life orbit the core, each stored item hangs off its area, and
 * concept nodes join items that mention the same thing. Signals travel along
 * the connections; new memories pop in as they are saved. Pinch to zoom, drag
 * to move, tap a node to see what it holds and what it is connected to.
 */
@Composable
fun BrainScreen(onBack: () -> Unit, onOpenChat: (Long) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val facts by c.memory.facts.collectAsStateWithLifecycle(initialValue = emptyList())
    val todos by c.todos.todos.collectAsStateWithLifecycle(initialValue = emptyList())
    val reminders by c.db.reminders().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val docs by c.knowledge.documents.collectAsStateWithLifecycle(initialValue = emptyList())
    val conversations by c.conversations.conversations.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val glow = settings?.glow ?: true

    var hidden by remember { mutableStateOf(emptySet<BrainNodeType>()) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedKey by remember { mutableStateOf<String?>(null) }

    val allItems = remember(facts, todos, reminders, docs, conversations) {
        buildList {
            facts.forEach { add(BrainItem(BrainNodeType.FACT, it.id, it.text, it.createdAt, pinned = it.pinned)) }
            todos.forEach { add(BrainItem(BrainNodeType.TASK, it.id, it.title, it.createdAt, done = it.done)) }
            reminders.filter { it.status == "scheduled" }.forEach { add(BrainItem(BrainNodeType.REMINDER, it.id, it.text, it.triggerAt)) }
            docs.forEach { add(BrainItem(BrainNodeType.DOCUMENT, it.id, it.name, it.indexedAt)) }
            conversations.filter { it.title != "New conversation" }.take(14).forEach { add(BrainItem(BrainNodeType.CONVERSATION, it.id, it.title, it.updatedAt)) }
        }
    }
    val counts = remember(allItems) { allItems.groupingBy { it.type }.eachCount() }
    val items = remember(allItems, hidden) { allItems.filter { it.type !in hidden } }

    var graph by remember { mutableStateOf(BrainGraph.EMPTY) }
    var layout by remember { mutableStateOf(BrainLayout()) }
    val births = remember { HashMap<String, Long>() }
    var frame by remember { mutableLongStateOf(0L) }
    var startFrame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(items) {
        val next = withContext(Dispatchers.Default) { BrainBuilder.build(items) }
        if (layout.graph.nodes.isEmpty()) {
            // First time: unfold the brain off-screen so it appears already organized.
            layout = withContext(Dispatchers.Default) { BrainLayout().also { it.setGraph(next); it.settle() } }
        } else {
            layout.setGraph(next).forEach { births[it] = frame }
        }
        graph = next
        if (selectedKey != null && next.indexOf(selectedKey!!) < 0) selectedKey = null
    }

    // Camera. Until the user moves it, the view keeps the whole brain in frame.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var userMoved by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<String?>(null) }

    val currentLayout by rememberUpdatedState(layout)
    val currentGraph by rememberUpdatedState(graph)

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (startFrame == 0L) startFrame = now
                val dt = if (last == 0L) 0.016f else (now - last) / 1e9f
                last = now
                val l = currentLayout
                if (l.graph === currentGraph) l.step(dt)
                val size = canvasSize
                if (size != IntSize.Zero) {
                    if (!userMoved) {
                        val fit = (min(size.width, size.height) / 2f / (l.radius() + 110f)).coerceIn(0.22f, 1.5f)
                        scale = if (scale == 0f) fit else scale + (fit - scale) * 0.08f
                        offset = offset * 0.9f
                    }
                    focus?.let { key ->
                        val i = l.graph.indexOf(key)
                        if (i < 0) focus = null else {
                            val target = Offset(-l.x[i] * scale, -l.y[i] * scale - size.height * 0.12f)
                            offset += (target - offset) * 0.14f
                            if ((target - offset).getDistance() < 2f) focus = null
                        }
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
        if (key != null && center) { userMoved = true; if (scale < 0.9f) scale = 0.9f; focus = key }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Live Brain", onBack,
            subtitle = if (graph.itemCount == 0) "Nothing stored yet" else "${graph.itemCount} things known · ${graph.edges.size} connections · ${graph.conceptCount} concepts",
            actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Outlined.Search, "Search the brain", tint = if (searching) ZedColors.Cyan else ZedColors.Text) }
                IconButton(onClick = { userMoved = false; focus = null }) { Icon(Icons.Outlined.CenterFocusStrong, "Show the whole brain") }
            },
        )
        if (searching) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search what ZED knows") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                trailingIcon = { if (query.isNotEmpty()) Text("${matches.size}", color = ZedColors.Cyan, modifier = Modifier.padding(end = 12.dp)) },
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Canvas(
                Modifier.fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (scale <= 0f) return@detectTransformGestures
                            userMoved = true; focus = null
                            val next = (scale * zoom).coerceIn(0.18f, 4f)
                            val pivot = centroid - Offset(size.width / 2f, size.height / 2f)
                            offset = pivot - (pivot - offset) * (next / scale) + pan
                            scale = next
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            val l = currentLayout
                            val g = l.graph
                            if (scale <= 0f || g !== currentGraph) return@detectTapGestures
                            val cx = size.width / 2f + offset.x; val cy = size.height / 2f + offset.y
                            var best = -1; var bestD = Float.MAX_VALUE
                            for (i in g.nodes.indices) {
                                val dx = cx + l.x[i] * scale - tap.x; val dy = cy + l.y[i] * scale - tap.y
                                val d = sqrt(dx * dx + dy * dy)
                                val reach = nodeRadius(g.nodes[i], scale, density) + 18.dp.toPx()
                                if (d < reach && d < bestD) { best = i; bestD = d }
                            }
                            selectedKey = if (best >= 0) g.nodes[best].key else null
                        }
                    },
            ) {
                val now = frame // read every frame: this is what keeps the brain moving
                val l = layout
                val g = l.graph
                if (g !== graph || scale <= 0f || g.nodes.isEmpty()) return@Canvas
                val t = (now - startFrame) / 1e9f
                val cx = size.width / 2f + offset.x; val cy = size.height / 2f + offset.y
                fun pos(i: Int) = Offset(cx + l.x[i] * scale, cy + l.y[i] * scale)
                val focusSet: Set<Int>? = when {
                    selected != null -> g.neighbors[selected].toSet() + selected
                    matches.isNotEmpty() -> matches
                    else -> null
                }

                // Connections, and the signals running along them.
                g.edges.forEachIndexed { e, edge ->
                    val a = pos(edge.a); val b = pos(edge.b)
                    val hot = selected != null && (edge.a == selected || edge.b == selected)
                    val dim = focusSet != null && !hot
                    val color = g.nodes[edge.b].type.color()
                    drawLine(
                        color.copy(alpha = when { hot -> 0.85f; dim -> 0.05f; else -> 0.10f + 0.14f * edge.strength }),
                        a, b, strokeWidth = if (hot) 2.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round,
                    )
                    if (!glow && !hot) return@forEachIndexed
                    if (dim) return@forEachIndexed
                    val period = if (hot) 0.9f else 2.2f + (e * 37 % 17) / 10f
                    val u = t / period + e * 0.618f
                    val cycle = floor(u).toInt()
                    if (!hot && (cycle * 7 + e * 13) % 4 != 0) return@forEachIndexed
                    val f = u - cycle
                    // Selected: signals leave the selected node. Otherwise the direction alternates.
                    val forward = if (hot) edge.a == selected else (cycle + e) % 2 == 0
                    val p = if (forward) a + (b - a) * f else b + (a - b) * f
                    val fade = sin(PI.toFloat() * f)
                    drawCircle(color.copy(alpha = 0.16f * fade), 7.dp.toPx(), p)
                    drawCircle(color.copy(alpha = 0.4f * fade), 3.5.dp.toPx(), p)
                    drawCircle(Color.White.copy(alpha = 0.9f * fade), 1.5.dp.toPx(), p)
                }

                g.nodes.forEachIndexed { i, node ->
                    val p = pos(i)
                    if (p.x < -80f || p.y < -80f || p.x > size.width + 80f || p.y > size.height + 80f) return@forEachIndexed
                    val color = node.type.color()
                    val isSelected = i == selected
                    val lit = focusSet == null || i in focusSet
                    var alpha = if (lit) 1f else 0.22f
                    if (node.done) alpha *= 0.55f
                    var r = nodeRadius(node, scale, density)

                    // New memories pop in with a ripple.
                    births[node.key]?.let { born ->
                        val age = (now - born) / 1e9f
                        if (age < 1.1f) {
                            val grow = (age / 0.45f).coerceIn(0f, 1f)
                            r *= grow * (1f + 0.35f * sin(PI.toFloat() * grow))
                            drawCircle(color.copy(alpha = (1f - age / 1.1f) * 0.7f), r + age * 70.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                    val breathe = 1f + 0.07f * sin(t * 1.6f + i * 0.9f)

                    if (node.type == BrainNodeType.CORE) drawCore(p, r, color, t, glow)
                    else {
                        if (glow) {
                            if (node.type == BrainNodeType.CATEGORY || node.type == BrainNodeType.CONCEPT || isSelected) {
                                val halo = r * 3.2f * breathe
                                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.38f * alpha), Color.Transparent), p, halo), halo, p)
                            } else {
                                drawCircle(color.copy(alpha = 0.10f * alpha), r * 2.5f * breathe, p)
                                drawCircle(color.copy(alpha = 0.16f * alpha), r * 1.6f * breathe, p)
                            }
                        }
                        drawCircle(color.copy(alpha = alpha), r, p)
                        drawCircle(Color.White.copy(alpha = 0.5f * alpha), r * 0.42f, p - Offset(r * 0.22f, r * 0.22f))
                        if (node.pinned) drawCircle(Color.White.copy(alpha = 0.8f * alpha), r + 2.5.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                    }
                    if (i in matches) drawCircle(ZedColors.Amber, r + 5.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                    if (isSelected) drawCircle(Color.White, r + 5.dp.toPx() + 1.5.dp.toPx() * sin(t * 4f), p, style = Stroke(2.dp.toPx()))

                    val hub = node.type == BrainNodeType.CORE || node.type == BrainNodeType.CATEGORY
                    val show = when {
                        !lit -> false
                        hub -> true
                        node.type == BrainNodeType.CONCEPT -> scale > 0.5f || focusSet != null
                        else -> scale > 1.1f || focusSet != null
                    }
                    if (show) {
                        val text = labels.getOrPut(node.key) {
                            measurer.measure(if (hub) node.label.uppercase() else node.label, if (hub) hubStyle else labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1)
                        }
                        drawText(text, color = if (hub || isSelected) ZedColors.Text else ZedColors.Text.copy(alpha = 0.85f), topLeft = Offset(p.x - text.size.width / 2f, p.y + r + 4.dp.toPx()))
                    }
                }
            }

            if (graph.itemCount == 0) {
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (allItems.isEmpty()) "ZED's brain is waiting for its first memory" else "Everything is filtered out", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (allItems.isEmpty()) "Say “remember that my sister's name is Ana”, add a task or set a reminder — and watch it appear here, connected to what ZED already knows."
                        else "Turn a type back on above to see it.",
                        style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            if (selected != null) {
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
                    Column(Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState())) {
                        Text(if (node.type == BrainNodeType.CATEGORY) node.label else node.text, style = MaterialTheme.typography.bodyLarge)
                        if (node.type == BrainNodeType.CATEGORY || node.type == BrainNodeType.CONCEPT)
                            Text(node.text, style = MaterialTheme.typography.bodySmall, color = ZedColors.Muted)
                        if (node.itemId != null && node.timestamp > 0)
                            Text((if (node.type == BrainNodeType.REMINDER) "Due " else "") + formatTime(node.timestamp) + if (node.done) " · done" else "", style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted, modifier = Modifier.padding(top = 2.dp))

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
                                    Text(if (other.type == BrainNodeType.CATEGORY) "Area" else other.type.label, style = MaterialTheme.typography.labelSmall, color = ZedColors.Muted)
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

private fun nodeRadius(node: BrainNode, scale: Float, density: Float): Float =
    (4f + 20f * node.weight) * density * scale.coerceIn(0.55f, 1.7f)

/** The core: a bright reactor with two counter-rotating arcs. */
private fun DrawScope.drawCore(p: Offset, r: Float, color: Color, t: Float, glow: Boolean) {
    val pulse = 1f + 0.08f * sin(t * 2.2f)
    if (glow) {
        val halo = r * 3.4f * pulse
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), ZedColors.Violet.copy(alpha = 0.12f), Color.Transparent), p, halo), halo, p)
    }
    drawCircle(Brush.radialGradient(listOf(Color.White, color, color.copy(alpha = 0.55f)), p, r), r, p)
    val ring = r * 1.45f
    drawArc(color.copy(alpha = 0.9f), t * 50f % 360f, 100f, false, Offset(p.x - ring, p.y - ring), Size(ring * 2, ring * 2), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    drawArc(color.copy(alpha = 0.9f), t * 50f % 360f + 180f, 100f, false, Offset(p.x - ring, p.y - ring), Size(ring * 2, ring * 2), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    val outer = r * 1.9f
    drawArc(ZedColors.Violet.copy(alpha = 0.8f), 360f - t * 32f % 360f, 60f, false, Offset(p.x - outer, p.y - outer), Size(outer * 2, outer * 2), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
    drawArc(ZedColors.Violet.copy(alpha = 0.8f), 360f - t * 32f % 360f + 180f, 60f, false, Offset(p.x - outer, p.y - outer), Size(outer * 2, outer * 2), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
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
