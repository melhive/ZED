package com.melhive.zed.brain

import com.melhive.zed.AppContainer
import com.melhive.zed.core.brain.BrainBuilder
import com.melhive.zed.core.brain.BrainEvent
import com.melhive.zed.core.brain.BrainGraph
import com.melhive.zed.core.brain.BrainItem
import com.melhive.zed.core.brain.BrainLayout
import com.melhive.zed.core.brain.BrainNodeType
import com.melhive.zed.core.brain.BrainStrength
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Turns everything ZED stores into Live Brain items, and keeps a ready-made,
 * already-unfolded copy of the brain for the small brains on Home, in LIVE
 * and in the floating assistant. Nothing is computed while nobody is looking.
 */
class BrainHub(scope: CoroutineScope, c: AppContainer) {
    /** Memory events (saved, recalled) for the brains to react to. */
    val events: SharedFlow<BrainEvent> = c.memory.events

    /** How many times ZED has recalled something since the app started. */
    val recalls = MutableStateFlow(0)

    val items: Flow<List<BrainItem>> = combine(
        c.memory.facts, c.todos.todos, c.db.reminders().observeAll(), c.knowledge.documents, c.conversations.conversations,
    ) { facts, todos, reminders, docs, conversations ->
        val now = System.currentTimeMillis()
        buildList {
            facts.forEach { add(BrainItem(BrainNodeType.FACT, it.id, it.text, it.createdAt, pinned = it.pinned, strength = BrainStrength.of(it.uses, it.lastUsedAt, it.createdAt, it.pinned, now))) }
            todos.forEach { add(BrainItem(BrainNodeType.TASK, it.id, it.title, it.createdAt, done = it.done, strength = if (it.done) 0.15f else BrainStrength.of(0, 0, it.createdAt, false, now) + 0.15f)) }
            reminders.filter { it.status == "scheduled" }.forEach { add(BrainItem(BrainNodeType.REMINDER, it.id, it.text, it.createdAt, strength = 0.75f)) }
            docs.forEach { add(BrainItem(BrainNodeType.DOCUMENT, it.id, it.name, it.indexedAt, strength = BrainStrength.of(0, 0, it.indexedAt, false, now))) }
            conversations.filter { it.title != "New conversation" }.take(14).forEach { add(BrainItem(BrainNodeType.CONVERSATION, it.id, it.title, it.updatedAt, strength = BrainStrength.of(0, 0, it.updatedAt, false, now))) }
        }
    }

    /** Labelled relationships ("Ana —sister→ you"), drawn as named connections. */
    val links: Flow<List<BrainBuilder.Link>> = c.db.relations().observeAll().map { list -> list.map { BrainBuilder.Link(it.subject, it.relation, it.target) } }

    /** A settled brain: the graph and where each node sits. */
    class Snapshot(val graph: BrainGraph, val x: FloatArray, val y: FloatArray, val z: FloatArray, val radius: Float)

    private val layout = BrainLayout()

    val snapshot: StateFlow<Snapshot?> = combine(items, links) { a, b -> a to b }.map { (list, links) ->
        val graph = BrainBuilder.build(list, links = links)
        val first = layout.graph.nodes.isEmpty()
        layout.setGraph(graph)
        layout.settle(if (first) 1500 else 240)
        Snapshot(graph, layout.x.copyOf(), layout.y.copyOf(), layout.z.copyOf(), layout.radius())
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        scope.launch { events.collect { if (it is BrainEvent.Recalled) recalls.value += 1 } }
    }
}
