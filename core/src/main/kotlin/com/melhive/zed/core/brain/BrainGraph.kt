package com.melhive.zed.core.brain

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What a point in ZED's brain stands for. */
enum class BrainNodeType(val label: String, val plural: String) {
    CORE("ZED", "ZED"),
    CATEGORY("Area", "Areas"),
    CONCEPT("Concept", "Concepts"),
    FACT("Memory", "Memories"),
    TASK("Task", "Tasks"),
    REMINDER("Reminder", "Reminders"),
    DOCUMENT("Document", "Documents"),
    CONVERSATION("Conversation", "Conversations"),
}

/** Something ZED has stored: a remembered fact, a task, a reminder, an indexed document or a conversation. */
data class BrainItem(
    val type: BrainNodeType,
    val id: Long,
    val text: String,
    val timestamp: Long,
    val pinned: Boolean = false,
    val done: Boolean = false,
    /** How alive this memory is, 0 (fading) to 1 (used often and recently). See [BrainStrength]. */
    val strength: Float = 0.5f,
)

/**
 * Memory behaves like memory: what ZED uses often and recently stays strong,
 * what it never touches slowly fades (it is never deleted — only drawn dimmer
 * and farther from the core).
 */
object BrainStrength {
    private const val DAY = 86_400_000.0

    fun of(uses: Int, lastUsedAt: Long, createdAt: Long, pinned: Boolean, now: Long): Float {
        val touched = maxOf(lastUsedAt, createdAt)
        val days = ((now - touched).coerceAtLeast(0) / DAY)
        val recency = Math.exp(-days / 30.0)
        val usage = 1 - Math.exp(-uses.coerceAtLeast(0) / 4.0)
        val v = (0.15 + 0.45 * usage + 0.40 * recency).coerceIn(0.0, 1.0).toFloat()
        return if (pinned) maxOf(v, 0.85f) else v
    }
}

/** Something that just happened in ZED's mind, for the Live Brain to show. Keys match [BrainNode.key]. */
sealed interface BrainEvent {
    /** ZED used these memories to answer. */
    data class Recalled(val keys: List<String>) : BrainEvent
    /** ZED stored something new. */
    data class Saved(val key: String) : BrainEvent
}

data class BrainNode(
    /** Stable across rebuilds (e.g. "FACT:12", "concept:ana"), so the picture doesn't jump when data changes. */
    val key: String,
    val type: BrainNodeType,
    val label: String,
    val text: String,
    /** Relative size, 0.25 (a single item) to 1 (the core). */
    val weight: Float,
    val category: String? = null,
    val itemId: Long? = null,
    val timestamp: Long = 0,
    val pinned: Boolean = false,
    val done: Boolean = false,
    val strength: Float = 0.5f,
)

/** A connection. [label] names a relationship ("sister", "works at") when ZED knows one. */
data class BrainEdge(val a: Int, val b: Int, val strength: Float, val label: String? = null)

class BrainGraph(val nodes: List<BrainNode>, val edges: List<BrainEdge>) {
    /** For each node, the indices of the nodes it is connected to. */
    val neighbors: List<IntArray> = run {
        val lists = List(nodes.size) { mutableListOf<Int>() }
        for (e in edges) { lists[e.a] += e.b; lists[e.b] += e.a }
        lists.map { it.toIntArray() }
    }

    val itemCount: Int get() = nodes.count { it.itemId != null }
    val conceptCount: Int get() = nodes.count { it.type == BrainNodeType.CONCEPT }

    fun indexOf(key: String): Int = nodes.indexOfFirst { it.key == key }

    /** The relationship label between two nodes, if their connection has one. */
    fun edgeLabel(a: Int, b: Int): String? = edges.firstOrNull { (it.a == a && it.b == b) || (it.a == b && it.b == a) }?.label

    /** Nodes whose label or text mentions [query], best match (exact name) first. */
    fun find(query: String): List<Int> {
        val q = query.trim().lowercase().removePrefix("my ").trim()
        if (q.isEmpty()) return emptyList()
        return nodes.indices.filter { nodes[it].type != BrainNodeType.CORE && (nodes[it].label.lowercase().contains(q) || nodes[it].text.lowercase().contains(q)) }
            .sortedWith(compareBy<Int> { if (nodes[it].label.equals(q, true)) 0 else if (nodes[it].type == BrainNodeType.CONCEPT) 1 else 2 })
    }

    /** Nodes whose text contains every word of [query]. */
    fun search(query: String): List<Int> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return nodes.indices.filter { i ->
            val n = nodes[i]
            n.type != BrainNodeType.CORE && words.all { w -> n.text.lowercase().contains(w) || n.label.lowercase().contains(w) }
        }
    }

    /** The chain of nodes from the core to [index] (core first), or just [index] if it isn't connected. */
    fun pathFromCore(index: Int): List<Int> {
        if (index !in nodes.indices) return emptyList()
        val parent = IntArray(nodes.size) { -1 }
        val seen = BooleanArray(nodes.size)
        val queue = ArrayDeque<Int>()
        queue += 0; seen[0] = true
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n == index) break
            for (m in neighbors[n]) if (!seen[m]) { seen[m] = true; parent[m] = n; queue += m }
        }
        if (!seen[index]) return listOf(index)
        val path = ArrayList<Int>()
        var at = index
        while (at != -1) { path += at; at = parent[at] }
        return path.asReversed()
    }

    /** Oldest and newest item timestamps, or null when nothing is stored. */
    fun timeRange(): Pair<Long, Long>? {
        val times = nodes.filter { it.itemId != null && it.timestamp > 0 }.map { it.timestamp }
        return if (times.isEmpty()) null else times.min() to times.max()
    }

    /**
     * What the brain looked like at [cutoff]: items stored by then, the areas
     * they belong to, and concepts that already linked at least two of them.
     */
    fun visibleAt(cutoff: Long): BooleanArray {
        val visible = BooleanArray(nodes.size)
        for (i in nodes.indices) { val n = nodes[i]; if (n.type == BrainNodeType.CORE || (n.itemId != null && n.timestamp <= cutoff)) visible[i] = true }
        for (i in nodes.indices) when (nodes[i].type) {
            BrainNodeType.CATEGORY -> visible[i] = neighbors[i].any { nodes[it].itemId != null && visible[it] }
            BrainNodeType.CONCEPT -> visible[i] = neighbors[i].count { nodes[it].itemId != null && visible[it] } >= 2
            else -> Unit
        }
        return visible
    }

    companion object { val EMPTY = BrainGraph(emptyList(), emptyList()) }
}

/**
 * Organizes what ZED stores into a knowledge graph, the way a note-taking
 * vault links notes: the core in the middle, areas of life around it (People,
 * Work, Health…), every stored item attached to its area, and concept nodes
 * joining items that talk about the same thing ("Ana", "dentist", "Cebu").
 * Nothing is saved: the graph is rebuilt from the stored items, so it can
 * never disagree with them.
 */
object BrainBuilder {
    private class Area(val name: String, vararg words: String) { val words = words.toSet() }

    private val AREAS = listOf(
        Area("You", "birthday", "born", "age", "myself", "nickname", "height", "weight", "zodiac", "pronouns"),
        Area("People", "sister", "brother", "mom", "mother", "dad", "father", "wife", "husband", "girlfriend", "boyfriend", "friend", "son",
            "daughter", "kid", "kids", "child", "children", "family", "cousin", "aunt", "uncle", "grandma", "grandpa", "partner", "boss",
            "colleague", "coworker", "neighbor", "teacher", "baby", "parents", "fiance", "fiancee", "nephew", "niece"),
        Area("Places", "live", "lives", "address", "city", "home", "house", "office", "street", "country", "town", "apartment", "hometown",
            "located", "location", "province", "barangay", "building", "airport"),
        Area("Preferences", "like", "likes", "love", "loves", "favorite", "favourite", "prefer", "prefers", "hate", "hates", "dislike",
            "allergic", "allergy", "enjoy", "enjoys", "hobby", "hobbies", "always", "never", "usually"),
        Area("Work", "work", "works", "job", "project", "projects", "meeting", "client", "clients", "deadline", "company", "business", "salary",
            "career", "team", "report", "presentation", "email", "invoice", "interview", "school", "class", "exam", "study", "homework", "code",
            "app", "release", "deploy"),
        Area("Health", "doctor", "medicine", "medication", "pill", "pills", "gym", "workout", "exercise", "sleep", "diet", "dentist", "hospital",
            "vitamin", "vitamins", "checkup", "therapy", "blood", "run", "running", "water", "stretch"),
        Area("Money", "pay", "bank", "bill", "bills", "budget", "price", "rent", "loan", "debt", "money", "payment", "tax", "taxes", "savings",
            "insurance", "subscription", "wallet", "gcash", "credit"),
    )

    private val FALLBACK = mapOf(
        BrainNodeType.FACT to "Notes",
        BrainNodeType.TASK to "Tasks",
        BrainNodeType.REMINDER to "Schedule",
        BrainNodeType.DOCUMENT to "Documents",
        BrainNodeType.CONVERSATION to "Conversations",
    )

    private val STOP = setOf(
        "the", "and", "for", "are", "was", "were", "this", "that", "with", "from", "you", "your", "what", "how", "does", "did", "has", "have",
        "had", "not", "but", "can", "will", "would", "should", "could", "about", "into", "than", "then", "them", "they", "their", "there", "here",
        "when", "where", "which", "who", "why", "all", "any", "some", "more", "most", "very", "just", "also", "too", "its", "his", "her",
        "she", "him", "our", "out", "off", "over", "under", "again", "every", "each", "one", "two", "new", "get", "got", "let", "say", "said",
        "tell", "told", "ask", "please", "zed", "hey", "remember", "remind", "reminder", "add", "task", "note", "need", "want", "going", "make",
        "made", "been", "being", "don", "doesn", "isn", "i'm", "it's", "today", "tomorrow", "tonight", "yesterday", "name", "like", "likes",
        "ang", "mga", "para", "yung", "ako", "ikaw", "siya", "kay", "nga", "lang", "naman", "din", "rin", "pero", "kung", "ito", "iyan",
        "conversation", "minutes", "minute", "hours", "hour",
    )

    private val ABOUT_ME = Regex("^(my name is|i am|i'm|call me|my birthday|my age|my nickname)\\b", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("[^\\p{L}\\p{N}']+")

    /** Lowercased, lightly stemmed key for a word, or null when the word carries no meaning on its own. */
    internal fun conceptKey(word: String): String? {
        val w = word.trim('\'').lowercase().removeSuffix("'s")
        if (w.length < 3 || w in STOP || w.all { it.isDigit() }) return null
        return if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w
    }

    private fun areaOf(item: BrainItem, words: List<String>): String {
        if (item.type == BrainNodeType.FACT || item.type == BrainNodeType.TASK || item.type == BrainNodeType.REMINDER) {
            val lower = words.map { it.lowercase().removeSuffix("'s") }
            // "My name is…" / "I am…" describe the user; a bare "name" elsewhere ("my sister's name") does not.
            if (item.type == BrainNodeType.FACT && ABOUT_ME.containsMatchIn(item.text.trim())) return "You"
            val best = AREAS.map { a -> a to lower.count { it in a.words } }.filter { it.second > 0 }.maxByOrNull { it.second }
            if (best != null) return best.first.name
        }
        return FALLBACK[item.type] ?: "Notes"
    }

    private fun shorten(text: String, max: Int = 30): String {
        val line = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
    }

    /** A labelled relationship to draw: (subject) —relation→ (target). "me" is the user. */
    data class Link(val subject: String, val relation: String, val target: String)

    fun build(items: List<BrainItem>, maxItems: Int = 110, maxConcepts: Int = 26, links: List<Link> = emptyList()): BrainGraph {
        val kept = items.filter { it.text.isNotBlank() }
            .sortedWith(compareByDescending<BrainItem> { it.pinned }.thenByDescending { it.timestamp })
            .take(maxItems)

        val nodes = mutableListOf<BrainNode>()
        val edges = mutableListOf<BrainEdge>()
        nodes += BrainNode("core", BrainNodeType.CORE, "ZED", "Everything ZED knows, and how it connects.", 1f)

        val words = kept.map { it.text.split(SPLIT).filter { w -> w.isNotBlank() } }
        val areas = kept.mapIndexed { i, item -> areaOf(item, words[i]) }

        // Areas, in a fixed order so the picture is stable.
        val order = AREAS.map { it.name } + listOf("Notes", "Tasks", "Schedule", "Documents", "Conversations")
        val areaIndex = HashMap<String, Int>()
        for (name in order) {
            val count = areas.count { it == name }
            if (count == 0) continue
            areaIndex[name] = nodes.size
            val weight = min(0.85f, 0.5f + 0.08f * ln(1f + count))
            edges += BrainEdge(0, nodes.size, 1f)
            nodes += BrainNode("area:$name", BrainNodeType.CATEGORY, name, "$count ${if (count == 1) "item" else "items"} in $name", weight, category = name)
        }

        val itemIndex = IntArray(kept.size)
        kept.forEachIndexed { i, item ->
            itemIndex[i] = nodes.size
            edges += BrainEdge(areaIndex.getValue(areas[i]), nodes.size, 0.7f)
            nodes += BrainNode(
                key = "${item.type.name}:${item.id}", type = item.type, label = shorten(item.text), text = item.text.trim(),
                weight = 0.24f + 0.18f * item.strength.coerceIn(0f, 1f) + if (item.pinned) 0.04f else 0f,
                category = areas[i], itemId = item.id, timestamp = item.timestamp,
                pinned = item.pinned, done = item.done, strength = item.strength.coerceIn(0f, 1f),
            )
        }

        // Concepts: meaningful words shared by at least two items. Names (capitalized mid-sentence) come first.
        class Concept(val key: String) { val items = LinkedHashSet<Int>(); val spellings = HashMap<String, Int>(); var proper = 0 }
        val concepts = LinkedHashMap<String, Concept>()
        val areaNames = order.map { it.lowercase() }.toSet()
        words.forEachIndexed { i, list ->
            list.forEachIndexed { pos, raw ->
                val key = conceptKey(raw) ?: return@forEachIndexed
                if (key in areaNames) return@forEachIndexed
                val c = concepts.getOrPut(key) { Concept(key) }
                c.items += i
                val clean = raw.trim('\'').removeSuffix("'s")
                c.spellings[clean] = (c.spellings[clean] ?: 0) + 1
                if (pos > 0 && clean.first().isUpperCase()) c.proper++
            }
        }
        val tooCommon = if (kept.size >= 8) kept.size / 2 else Int.MAX_VALUE
        concepts.values
            .filter { it.items.size in 2..tooCommon }
            .sortedWith(compareByDescending<Concept> { it.items.size + if (it.proper > 0) 2 else 0 }.thenBy { it.key })
            .take(maxConcepts)
            .forEach { c ->
                val index = nodes.size
                val spelling = c.spellings.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
                val label = if (c.proper > 0) spelling.replaceFirstChar { it.uppercase() } else spelling.lowercase()
                nodes += BrainNode(
                    "concept:${c.key}", BrainNodeType.CONCEPT, label, "“$label” connects ${c.items.size} things ZED knows.",
                    min(0.6f, 0.34f + 0.05f * c.items.size),
                )
                for (i in c.items) edges += BrainEdge(index, itemIndex[i], 0.5f)
            }

        // Labelled relationships: each side becomes a named node (reusing a concept for the same word), joined by a labelled edge.
        if (links.isNotEmpty()) {
            val keyIndex = HashMap<String, Int>().apply { nodes.forEachIndexed { i, n -> put(n.key, i) } }
            fun entity(name: String): Int {
                if (name.equals("me", true)) return areaIndex["You"] ?: 0
                val words = name.split(SPLIT).mapNotNull { conceptKey(it) }
                val key = if (words.size == 1) "concept:${words[0]}" else "entity:" + name.lowercase().trim()
                keyIndex[key]?.let { return it }
                val i = nodes.size
                nodes += BrainNode(key, BrainNodeType.CONCEPT, name.trim(), "“${name.trim()}” — someone or something ZED knows about.", 0.4f)
                keyIndex[key] = i
                // Connect the new name to the memories that mention it.
                val needle = name.lowercase()
                kept.forEachIndexed { k, item -> if (item.text.lowercase().contains(needle)) edges += BrainEdge(i, itemIndex[k], 0.5f) }
                return i
            }
            links.take(40).forEach { l ->
                val a = entity(l.subject); val b = entity(l.target)
                if (a != b && edges.none { ((it.a == a && it.b == b) || (it.a == b && it.b == a)) && it.label == l.relation }) edges += BrainEdge(a, b, 0.8f, l.relation)
            }
        }
        return BrainGraph(nodes, edges)
    }
}

/** Which meaningful words a piece of text carries — the same notion of "meaningful" the brain's concepts use. */
object BrainRecall {
    private val SPLIT = Regex("[^\\p{L}\\p{N}']+")
    fun keys(text: String): Set<String> = text.split(SPLIT).mapNotNull { BrainBuilder.conceptKey(it) }.toSet()
}

/**
 * A small force-directed layout in three dimensions: connected nodes pull
 * together, all nodes push apart, everything is drawn gently toward the core
 * (strong memories more than faint ones), and a slow drift keeps it alive.
 * Positions are in abstract units (the core sits at 0,0,0 and a typical brain
 * spans roughly ±400).
 */
class BrainLayout {
    var graph: BrainGraph = BrainGraph.EMPTY
        private set
    var x = FloatArray(0); private set
    var y = FloatArray(0); private set
    var z = FloatArray(0); private set
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var vz = FloatArray(0)
    private var time = 0f

    /** Swaps in a new graph, keeping the position of every node that still exists. Returns the keys that are new. */
    fun setGraph(next: BrainGraph): Set<String> {
        val old = HashMap<String, Int>()
        graph.nodes.forEachIndexed { i, n -> old[n.key] = i }
        val size = next.nodes.size
        val nx = FloatArray(size); val ny = FloatArray(size); val nz = FloatArray(size)
        val nvx = FloatArray(size); val nvy = FloatArray(size); val nvz = FloatArray(size)
        val placed = BooleanArray(size)
        val added = LinkedHashSet<String>()
        val areas = next.nodes.count { it.type == BrainNodeType.CATEGORY }.coerceAtLeast(1)
        var areaSeen = 0
        next.nodes.forEachIndexed { i, n ->
            val was = old[n.key]
            if (was != null) { nx[i] = x[was]; ny[i] = y[was]; nz[i] = z[was]; nvx[i] = vx[was]; nvy[i] = vy[was]; nvz[i] = vz[was]; placed[i] = true }
            if (n.type == BrainNodeType.CATEGORY) areaSeen++
            if (was != null) return@forEachIndexed
            added += n.key
            // Deterministic scatter, so the same data always grows the same brain.
            val h = n.key.hashCode()
            val angle = ((h and 0xffff) / 65535f) * 6.2831855f
            val lift = (((h ushr 8) and 0xff) / 255f) * 2f - 1f
            val flat = sqrt(1f - lift * lift)
            val reach = 50f + ((h ushr 16) and 0xff) / 255f * 50f
            when (n.type) {
                BrainNodeType.CORE -> Unit
                BrainNodeType.CATEGORY -> {
                    // Areas are spread evenly over a sphere around the core (golden-angle spiral).
                    val k = areaSeen - 1
                    val up = if (areas == 1) 0f else 1f - 2f * (k + 0.5f) / areas
                    val ring = sqrt(1f - up * up)
                    val a = k * 2.3999632f
                    nx[i] = 250f * ring * cos(a); ny[i] = 250f * up; nz[i] = 250f * ring * sin(a)
                }
                else -> {
                    val anchors = next.neighbors[i].filter { placed[it] }
                    val ax = if (anchors.isEmpty()) 0f else anchors.map { nx[it] }.average().toFloat()
                    val ay = if (anchors.isEmpty()) 0f else anchors.map { ny[it] }.average().toFloat()
                    val az = if (anchors.isEmpty()) 0f else anchors.map { nz[it] }.average().toFloat()
                    nx[i] = ax + reach * flat * cos(angle); ny[i] = ay + reach * lift; nz[i] = az + reach * flat * sin(angle)
                }
            }
            placed[i] = true
        }
        graph = next; x = nx; y = ny; z = nz; vx = nvx; vy = nvy; vz = nvz
        return if (old.isEmpty()) emptySet() else added
    }

    private fun rest(e: BrainEdge): Float {
        val a = graph.nodes[e.a]; val b = graph.nodes[e.b]
        return when {
            a.type == BrainNodeType.CORE || b.type == BrainNodeType.CORE -> 250f
            a.type == BrainNodeType.CATEGORY || b.type == BrainNodeType.CATEGORY -> 120f
            else -> 100f
        }
    }

    /** Advances the simulation by [dt] seconds (clamped, so a long pause can't explode it). */
    fun step(dt: Float = 1f / 60f) {
        val n = graph.nodes.size
        if (n == 0) return
        val k = (dt.coerceIn(0.001f, 0.05f)) * 60f
        time += dt.coerceIn(0.001f, 0.05f)
        val fx = FloatArray(n); val fy = FloatArray(n); val fz = FloatArray(n)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                var dx = x[i] - x[j]; var dy = y[i] - y[j]; var dz = z[i] - z[j]
                var d2 = dx * dx + dy * dy + dz * dz
                if (d2 < 1f) { dx = 0.5f + (i % 3) * 0.3f; dy = 0.5f - (j % 3) * 0.3f; dz = 0.4f - (i % 2) * 0.8f; d2 = dx * dx + dy * dy + dz * dz }
                if (d2 > 250_000f) continue
                val push = 5200f * (0.6f + graph.nodes[i].weight + graph.nodes[j].weight) / d2
                val d = sqrt(d2)
                val px = dx / d * push; val py = dy / d * push; val pz = dz / d * push
                fx[i] += px; fy[i] += py; fz[i] += pz; fx[j] -= px; fy[j] -= py; fz[j] -= pz
            }
        }
        for (e in graph.edges) {
            val dx = x[e.b] - x[e.a]; val dy = y[e.b] - y[e.a]; val dz = z[e.b] - z[e.a]
            val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01f)
            val pull = 0.035f * e.strength * (d - rest(e))
            val px = dx / d * pull; val py = dy / d * pull; val pz = dz / d * pull
            fx[e.a] += px; fy[e.a] += py; fz[e.a] += pz; fx[e.b] -= px; fy[e.b] -= py; fz[e.b] -= pz
        }
        for (i in 0 until n) {
            val node = graph.nodes[i]
            if (node.type == BrainNodeType.CORE) { x[i] = 0f; y[i] = 0f; z[i] = 0f; vx[i] = 0f; vy[i] = 0f; vz[i] = 0f; continue }
            // Strong memories are held closer to the core; faint ones drift to the edge.
            val gravity = if (node.itemId != null) 0.002f + 0.007f * node.strength else 0.004f
            fx[i] -= x[i] * gravity; fy[i] -= y[i] * gravity; fz[i] -= z[i] * gravity
            // Slow drift: the brain never sits perfectly still.
            fx[i] += 0.035f * sin(time * 0.55f + i * 1.7f); fy[i] += 0.035f * cos(time * 0.47f + i * 2.3f); fz[i] += 0.035f * sin(time * 0.51f + i * 2.9f)
            vx[i] = ((vx[i] + fx[i] * k) * 0.86f).coerceIn(-14f, 14f)
            vy[i] = ((vy[i] + fy[i] * k) * 0.86f).coerceIn(-14f, 14f)
            vz[i] = ((vz[i] + fz[i] * k) * 0.86f).coerceIn(-14f, 14f)
            x[i] += vx[i] * k; y[i] += vy[i] * k; z[i] += vz[i] * k
        }
    }

    /** Runs the simulation ahead so a new brain appears already unfolded. */
    fun settle(steps: Int = 1500) { repeat(steps) { step() } }

    /** Distance from the core to the farthest node. */
    fun radius(): Float {
        var r = 0f
        for (i in x.indices) r = maxOf(r, sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i]))
        return r
    }

    /** Distance of node [i] from the core. */
    fun distance(i: Int): Float = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
}
