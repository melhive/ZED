package com.melhive.zed.agents

import com.melhive.zed.core.command.CommandParser
import com.melhive.zed.core.command.ParsedCommand
import com.melhive.zed.core.conversation.SmallTalk
import com.melhive.zed.core.intelligence.ContextWindow
import com.melhive.zed.core.intelligence.PromptBuilder
import com.melhive.zed.core.intelligence.Role
import com.melhive.zed.core.security.ToolCallExtractor
import com.melhive.zed.core.skills.SkillRegistry
import com.melhive.zed.core.tools.ToolRequest
import com.melhive.zed.intelligence.GenerationEvent
import com.melhive.zed.intelligence.GenerationRequest
import com.melhive.zed.intelligence.IntelligenceProvider
import com.melhive.zed.intelligence.LocalModelRuntime
import com.melhive.zed.intelligence.ProviderSource
import com.melhive.zed.knowledge.KnowledgeRepository
import com.melhive.zed.memory.ConversationRepository
import com.melhive.zed.memory.MemoryRepository
import com.melhive.zed.settings.SettingsRepository
import com.melhive.zed.tools.ToolManager
import com.melhive.zed.tools.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class AssistantPhase { IDLE, THINKING, EXECUTING, RESEARCHING, SEARCHING, COMPLETED, ERROR }

data class AssistantActivity(
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val detail: String? = null,
    val conversationId: Long? = null,
    val at: Long = System.currentTimeMillis(),
) {
    val busy: Boolean get() = phase in setOf(AssistantPhase.THINKING, AssistantPhase.EXECUTING, AssistantPhase.RESEARCHING, AssistantPhase.SEARCHING)
}

data class AssistantReply(val text: String, val ok: Boolean, val source: String)

/** An action ZED just finished, for result chips ("Alarm 6:00 ✓") and cards. */
data class ActionResult(val tool: String, val ok: Boolean, val chip: String?, val message: String, val conversationId: Long, val at: Long = System.currentTimeMillis())

/**
 * Handles one user turn end to end:
 *  1. Deterministic native commands and computations are tried first (no model needed).
 *  2. Otherwise the active intelligence provider holds the conversation, with
 *     enabled tools described in the system prompt. Without a provider, the
 *     native small-talk layer answers social messages and explains its limits.
 *  3. Tool calls in model output are extracted as untrusted requests and run
 *     through the ToolManager (validation → permission → confirmation).
 *  4. Results are fed back for up to [MAX_ROUNDS] rounds, then the final
 *     answer is persisted.
 */
class AssistantOrchestrator(
    private val appScope: CoroutineScope,
    private val conversations: ConversationRepository,
    private val memory: MemoryRepository,
    private val knowledge: KnowledgeRepository,
    private val providers: ProviderSource,
    private val tools: ToolManager,
    private val skills: SkillRegistry,
    private val settings: SettingsRepository,
) {
    private val parser = CommandParser()
    private val smallTalk = SmallTalk()
    private val _activity = MutableStateFlow(AssistantActivity())
    val activity: StateFlow<AssistantActivity> = _activity.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    /** Live text of the reply being generated (for streaming TTS / UI). */
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _results = kotlinx.coroutines.flow.MutableSharedFlow<ActionResult>(extraBufferCapacity = 16)
    /** Every action as it finishes. */
    val results: kotlinx.coroutines.flow.SharedFlow<ActionResult> = _results

    private val _lastUsage = MutableStateFlow<String?>(null)
    /** Token usage of the last online reply, when the provider reports it. */
    val lastUsage: StateFlow<String?> = _lastUsage.asStateFlow()

    private var currentJob: Job? = null
    /** Photo attached to the request being answered (sent with every step of this turn). */
    @Volatile private var currentImage: com.melhive.zed.intelligence.ImageInput? = null

    /** When the turn being answered began (elapsed-realtime nanoseconds), for reply timings. */
    @Volatile private var turnStartedAt = 0L

    /** Returns a routine name when the message is one of the user's routine phrases ("good night"). */
    var routineMatcher: (suspend (String) -> String?)? = null
    /** The user's own shortcuts ("lights out" → what it means), applied before anything else. */
    var aliases: (() -> Map<String, String>)? = null

    /** The phone's side of the awareness brief (time, place, battery, what's coming up). */
    var awareness: (suspend () -> com.melhive.zed.core.understanding.Awareness)? = null

    // ---- Understanding (follow-ups, corrections, tool repair) ----
    private val tracker = com.melhive.zed.core.understanding.DialogueTracker()
    private val repair = com.melhive.zed.core.understanding.ToolRepair(skills.catalog)
    private val dialogues = java.util.concurrent.ConcurrentHashMap<Long, com.melhive.zed.core.understanding.DialogueState>()
    /** What the user said in the turn being answered (dates and durations in it beat the model's). */
    @Volatile private var currentUserText: String? = null
    /** The conversation being answered (requests queued for the internet go back to it). */
    @Volatile var currentConversation: Long? = null
        private set
    /** Sources from web research done in this turn, listed under the answer. */
    @Volatile private var researchSources: List<String> = emptyList()

    /** Conversations where ZED asked "what's right?" and is waiting for the answer. */
    private val awaitingFix = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, String>>()

    /**
     * Learns from "that's wrong": the right answer is saved as a lesson (and as a
     * fact when it states one), so recall brings it back next time the topic comes up.
     */
    suspend fun recordCorrection(conversationId: Long, question: String, wrong: String, fix: String): String {
        val lesson = com.melhive.zed.core.learning.Corrections.lesson(question, wrong, fix)
        memory.remember(lesson, "correction")
        com.melhive.zed.core.learning.FactPatterns.extract(fix).forEach { memory.remember(it.text, "correction") }
        awaitingFix.remove(conversationId)
        return "Thanks — noted: $fix. I'll get that right next time."
    }

    /** The "Wrong" button on a reply: [fix] is what the user says is right. */
    suspend fun correctReply(conversationId: Long, messageId: Long, fix: String): String {
        val all = conversations.allMessages(conversationId)
        val i = all.indexOfFirst { it.id == messageId }
        val wrong = all.getOrNull(i)?.content.orEmpty()
        val question = (i - 1 downTo 0).firstOrNull { all[it].role == "user" }?.let { all[it].content }.orEmpty()
        val reply = recordCorrection(conversationId, question, wrong, fix.trim())
        conversations.addMessage(conversationId, "assistant", reply, source = "native")
        return reply
    }

    /** The last exchange before the current message: what the user asked and what ZED answered. */
    private suspend fun lastExchange(conversationId: Long): Pair<String, String>? {
        val all = conversations.allMessages(conversationId).dropLast(1) // the current message is already saved
        val answer = all.lastOrNull { it.role == "assistant" && it.status == "complete" } ?: return null
        val question = all.lastOrNull { it.role == "user" && it.id < answer.id }?.content.orEmpty()
        return question to answer.content
    }

    fun dialogue(conversationId: Long): com.melhive.zed.core.understanding.DialogueState = dialogues[conversationId] ?: com.melhive.zed.core.understanding.DialogueState()

    fun submit(conversationId: Long, text: String, attachmentName: String? = null, attachmentText: String? = null, voice: Boolean = false, image: com.melhive.zed.intelligence.ImageInput? = null, onReply: (AssistantReply) -> Unit = {}): Job {
        currentJob?.cancel()
        val job = appScope.launch {
            val reply = respond(conversationId, text, attachmentName, attachmentText, voice, image)
            onReply(reply)
        }
        currentJob = job
        return job
    }

    /** Discards the last answer and asks again with the same user message. */
    fun regenerate(conversationId: Long): Job? {
        currentJob?.cancel()
        val job = appScope.launch {
            val text = conversations.popLastExchange(conversationId) ?: return@launch
            val attachmentSplit = text.split("\n\n[Attached file: ", limit = 2)
            if (attachmentSplit.size == 2) {
                val name = attachmentSplit[1].substringBefore("]")
                respond(conversationId, attachmentSplit[0], name, attachmentSplit[1].substringAfter("]\n", ""))
            } else respond(conversationId, text)
        }
        currentJob = job
        return job
    }

    fun cancelCurrent() {
        currentJob?.cancel()
        providers.localRuntime.cancelActive()
    }

    private fun setPhase(p: AssistantPhase, detail: String? = null, conv: Long? = null) {
        _activity.value = AssistantActivity(p, detail, conv)
    }

    suspend fun respond(conversationId: Long, text: String, attachmentName: String? = null, attachmentText: String? = null, voice: Boolean = false, image: com.melhive.zed.intelligence.ImageInput? = null): AssistantReply {
        val trimmed = text.trim()
        val userContent = if (attachmentText != null) "$trimmed\n\n[Attached file: $attachmentName]\n$attachmentText" else trimmed
        conversations.addMessage(conversationId, "user", userContent, attachmentName = attachmentName ?: if (image != null) "📷 Photo" else null)
        currentImage = image
        turnStartedAt = System.nanoTime()
        setPhase(AssistantPhase.THINKING, conv = conversationId)
        _streamingText.value = ""
        return try {
            val provider = providers.active()
            // Local models: let the runtime decide from the file itself (it explains when a copy has no vision).
            val localSees = provider?.id == "local"
            if (image != null && (provider == null || (provider.id == "local" && !localSees))) {
                val msg = "This model can't look at photos. Gemma 4 E2B or E4B can, right on your phone: download one in Model Manager, or add a Gemini, OpenAI or Claude key."
                conversations.addMessage(conversationId, "assistant", msg, source = "system")
                setPhase(AssistantPhase.COMPLETED, null, conversationId)
                return AssistantReply(msg, false, "system")
            }
            // A shortcut the user taught stands for what they meant.
            val meant = runCatching { aliases?.invoke() }.getOrNull()?.let { com.melhive.zed.core.learning.Aliases.expand(trimmed, it) } ?: trimmed
            currentUserText = meant
            currentConversation = conversationId
            researchSources = emptyList()
            val plain = attachmentText == null && image == null
            val reply = if (plain) understood(conversationId, meant, modelAvailable = provider != null) else null
            val final = reply ?: if (provider != null) handleWithModel(conversationId, provider, voice, meant) else nativeConversation(conversationId, meant)
            // Instant (native) replies record how long the whole turn took; model replies are timed while streaming.
            runCatching { conversations.setTotalOnLastReply(conversationId, (System.nanoTime() - turnStartedAt) / 1_000_000) }
            setPhase(if (final.ok) AssistantPhase.COMPLETED else AssistantPhase.ERROR, if (final.ok) null else final.text, conversationId)
            final
        } catch (e: CancellationException) {
            setPhase(AssistantPhase.IDLE)
            throw e
        } catch (e: Exception) {
            com.melhive.zed.android.CrashReporter.recordNonFatal("assistant", e)
            val msg = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            conversations.addMessage(conversationId, "assistant", msg, status = "error", source = "system")
            setPhase(AssistantPhase.ERROR, msg, conversationId)
            AssistantReply(msg, false, "system")
        }
    }

    /**
     * Follows the conversation before anything else: instant social replies,
     * follow-ups that change the last action ("move that to 5"), pronouns
     * ("call her") and several requests in one sentence. Returns null when
     * the message should go on to the native parser's normal path or the model.
     */
    private suspend fun understood(conversationId: Long, text: String, modelAvailable: Boolean): AssistantReply? {
        com.melhive.zed.core.understanding.QuickReplies.reply(text, runCatching { memory.userName() }.getOrNull())?.let { r ->
            conversations.addMessage(conversationId, "assistant", r, source = "native")
            return AssistantReply(r, true, "native")
        }
        // "That's wrong": learn the right answer (or ask for it).
        when (val found = com.melhive.zed.core.learning.Corrections.detect(text)) {
            is com.melhive.zed.core.learning.Corrections.Found.WithFix -> lastExchange(conversationId)?.let { (q, a) ->
                val r = recordCorrection(conversationId, q, a, found.fix)
                conversations.addMessage(conversationId, "assistant", r, source = "native")
                return AssistantReply(r, true, "native")
            }
            com.melhive.zed.core.learning.Corrections.Found.WrongOnly -> lastExchange(conversationId)?.let { ex ->
                awaitingFix[conversationId] = ex
                val r = "Sorry about that — what's the right answer?"
                conversations.addMessage(conversationId, "assistant", r, source = "native")
                return AssistantReply(r, true, "native")
            }
            null -> awaitingFix[conversationId]?.let { (q, a) ->
                // The answer to "what's right?" — unless it is a new request.
                if (parser.parse(text) == ParsedCommand.NotACommand && !text.trim().endsWith("?")) {
                    val r = recordCorrection(conversationId, q, a, text.trim().trimEnd('.'))
                    conversations.addMessage(conversationId, "assistant", r, source = "native")
                    return AssistantReply(r, true, "native")
                }
                awaitingFix.remove(conversationId)
            }
        }
        val state = dialogue(conversationId).heard(text)
        dialogues[conversationId] = state
        val facts = runCatching { memory.list(200).map { it.text } }.getOrDefault(emptyList())
        return when (val r = tracker.resolve(text, state, facts)) {
            is com.melhive.zed.core.understanding.Resolution.Act -> {
                val outcome = runTool(conversationId, r.request)
                conversations.addMessage(conversationId, "assistant", outcome.message, source = "native", toolName = r.request.tool, status = if (outcome.isSuccess()) "complete" else "error")
                AssistantReply(outcome.message, outcome.isSuccess(), "native")
            }
            is com.melhive.zed.core.understanding.Resolution.Explain -> {
                conversations.addMessage(conversationId, "assistant", r.message, source = "native")
                AssistantReply(r.message, true, "native")
            }
            is com.melhive.zed.core.understanding.Resolution.Several -> {
                val replies = r.parts.mapNotNull { part -> handleNative(conversationId, part, modelAvailable = true) }
                if (replies.isEmpty()) null
                else AssistantReply(replies.joinToString(" ") { it.text }, replies.all { it.ok }, "native")
            }
            is com.melhive.zed.core.understanding.Resolution.Text -> handleNative(conversationId, r.text, modelAvailable)
        }
    }

    /**
     * Clear device commands and exact computations are handled natively (fast, reliable, offline).
     * When an AI model is available, conversational requests ("what can you do", requests missing
     * details) go to the model instead of fixed replies.
     */
    private suspend fun handleNative(conversationId: Long, text: String, modelAvailable: Boolean): AssistantReply? {
        runCatching { routineMatcher?.invoke(text.trim().trimEnd('.', '!', '?')) }.getOrNull()?.let { name ->
            val outcome = runTool(conversationId, ToolRequest("routine.run", mapOf("name" to name), ToolRequest.Origin.NATIVE))
            conversations.addMessage(conversationId, "assistant", outcome.message, source = "native", toolName = "routine.run", status = if (outcome.isSuccess()) "complete" else "error")
            return AssistantReply(outcome.message, outcome.isSuccess(), "native")
        }
        val cmd = parser.parse(text)
        if (modelAvailable && (cmd is ParsedCommand.Help || cmd is ParsedCommand.Clarify)) return null
        return when (cmd) {
            ParsedCommand.NotACommand -> null
            ParsedCommand.Help -> {
                conversations.addMessage(conversationId, "assistant", CommandParser.HELP_TEXT, source = "native")
                AssistantReply("Here is what I can do without an AI model. The list is on screen.", true, "native")
            }
            is ParsedCommand.Clarify -> {
                conversations.addMessage(conversationId, "assistant", cmd.message, source = "native")
                AssistantReply(cmd.message, true, "native")
            }
            is ParsedCommand.Answer -> {
                conversations.addMessage(conversationId, "assistant", cmd.text, source = "native")
                AssistantReply(cmd.text, true, "native")
            }
            // With a model, "research …" goes to it: it searches, reads and answers with sources.
            is ParsedCommand.Tool -> if (modelAvailable && cmd.request.tool == "web.research") null else {
                val outcome = runTool(conversationId, cmd.request)
                // Without a model, web findings are shown as they are (minus the instructions meant for a model).
                val shown = if (cmd.request.tool == "web.research" && outcome.isSuccess()) com.melhive.zed.core.web.Research.forPeople(outcome.message) else outcome.message
                conversations.addMessage(conversationId, "assistant", shown, source = "native", toolName = cmd.request.tool,
                    status = if (outcome.isSuccess()) "complete" else "error")
                AssistantReply(shown, outcome.isSuccess(), "native")
            }
        }
    }

    private fun ToolOutcome.isSuccess() = this is ToolOutcome.Executed && result.ok

    private suspend fun runTool(conversationId: Long, request: ToolRequest): ToolOutcome {
        val phase = when (request.tool) {
            "web.research" -> AssistantPhase.RESEARCHING
            "docs.search", "history.search", "file.read", "file.list" -> AssistantPhase.SEARCHING
            else -> AssistantPhase.EXECUTING
        }
        setPhase(phase, com.melhive.zed.core.ui.ActionLabels.progress(request.tool), conversationId)
        // Model-written calls get their everyday mistakes fixed (names, formats, the user's own times) first.
        val fixed = if (request.origin == ToolRequest.Origin.LOCAL_MODEL || request.origin == ToolRequest.Origin.ONLINE_MODEL)
            repair.repair(request, currentUserText).request else request
        val outcome = tools.execute(fixed)
        _results.tryEmit(ActionResult(fixed.tool, outcome.isSuccess(), com.melhive.zed.core.ui.ActionLabels.chip(fixed.tool, fixed.args), outcome.message, conversationId))
        if (outcome.isSuccess()) dialogues[conversationId] = dialogue(conversationId).after(fixed, outcome.message)
        if (fixed.tool == "web.research" && outcome.isSuccess()) researchSources = (researchSources + com.melhive.zed.core.web.Research.sourcesFrom(outcome.message)).distinct()
        return outcome
    }

    /** No model available: social replies or an honest, helpful explanation. */
    private suspend fun nativeConversation(conversationId: Long, text: String): AssistantReply {
        val ctx = SmallTalk.Context(
            userName = memory.userName(),
            addressAs = settings.current().userTitle.ifBlank { null },
            modelConfiguredButUnavailable = providers.anyConfigured(),
            hourOfDay = LocalTime.now().hour,
        )
        val reply = smallTalk.respond(text, ctx) ?: smallTalk.fallback(text, ctx)
        conversations.addMessage(conversationId, "assistant", reply, source = "native")
        return AssistantReply(reply, true, "native")
    }

    private suspend fun handleWithModel(conversationId: Long, provider: IntelligenceProvider, voice: Boolean, userText: String): AssistantReply {
        val s = settings.current()
        val isLocal = provider.id == "local"
        // Web research needs no key now; it is offered when the user allows it.
        val researchAvailable = s.webResearch
        // Very small on-device windows (e.g. 1280 tokens) cannot hold the tool list; such models just converse.
        val localContext = (provider as? com.melhive.zed.intelligence.LocalLiteRtProvider)?.contextTokens
        val tinyContext = localContext != null && localContext < 2048
        // Casual conversation gets a lean, persona-focused prompt; the tool list is included only when the
        // message looks like a device request (more room for the conversation, more natural replies).
        // On-device models get the same (compact) instructions every turn, so the runtime keeps its cached
        // conversation instead of re-reading the whole history whenever a message switches between chat and actions.
        val useTools = isLocal || PromptBuilder.looksLikeAction(userText)
        val enabled = skills.enabledTools(s.enabledSkills).filter { it.name != "web.research" || researchAvailable }
        val history0 = conversations.history(conversationId, 6)
        val previousUser = history0.dropLast(1).lastOrNull { it.role == Role.USER }?.content
        // On-device: only the few tools that fit this message (a small model chooses far better from four than from fifty).
        val toolSpecs = when {
            !useTools -> emptyList()
            isLocal -> com.melhive.zed.core.understanding.ToolSelector.select(userText, enabled, max = if (tinyContext) 3 else 5, context = previousUser).ifEmpty {
                // A question about the world: let Gemma search the web when its own knowledge may be stale or missing.
                if (researchAvailable && com.melhive.zed.core.understanding.ToolSelector.looksFactual(userText)) enabled.filter { it.name == "web.research" } else emptyList()
            }
            else -> enabled
        }
        val userName = memory.userName()
        // Memories connected to the question (through shared names and ideas), not just the newest ones.
        val now0 = System.currentTimeMillis()
        val allFacts = memory.list(300)
        val ranked = com.melhive.zed.core.understanding.MemoryRanker.rank(
            userText,
            allFacts.map { com.melhive.zed.core.understanding.MemoryFact(it.id, it.text, it.pinned, com.melhive.zed.core.brain.BrainStrength.of(it.uses, it.lastUsedAt, it.createdAt, it.pinned, now0)) },
            previous = previousUser, limit = if (tinyContext) 3 else 6,
        )
        val profile = buildList {
            userName?.let { add("name $it") }
            allFacts.filter { it.pinned && ranked.none { r -> r.fact.id == it.id } }.take(3).forEach { add(it.text) }
        }
        // What ZED learned by itself is marked, so the model treats it as less certain than what the user said.
        val sourceOf = allFacts.associate { it.id to it.source }
        fun label(f: com.melhive.zed.core.understanding.MemoryFact) = when (sourceOf[f.id]) {
            "learned" -> "${f.text} (learned, not confirmed)"
            "correction" -> "${f.text} (the user corrected you — follow this)"
            "preference" -> "${f.text} (the user's rule for how you answer — always follow it)"
            else -> f.text
        }
        // How the user wants ZED to answer: always included, whatever the question.
        val rules = allFacts.filter { it.source == "preference" }.take(8).map { "${it.text} (the user's rule for how you answer — always follow it)" }
        val memories = rules + if (isLocal) ranked.filter { sourceOf[it.fact.id] != "preference" }.map { label(it.fact) } else (ranked.map { it.fact.text } + allFacts.filter { it.pinned }.map { it.text } + allFacts.take(12).map { it.text }).distinct().take(30)
        // The memories this question touches grow stronger and light up in the Live Brain (off the reply path).
        if (ranked.isNotEmpty()) appScope.launch { runCatching { memory.markUsed(ranked.map { it.fact.id }) } }
        val environment = runCatching { awareness?.invoke() }.getOrNull()
        val computed = buildList {
            addAll(com.melhive.zed.core.understanding.Computations.find(userText, java.time.LocalDate.now()))
            com.melhive.zed.core.understanding.TimeHints.moment(userText, java.time.LocalDateTime.now())?.let { at ->
                add("the time mentioned = " + at.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", java.util.Locale.ENGLISH)) + " ($at)")
            }
        }
        val origin = if (isLocal) ToolRequest.Origin.LOCAL_MODEL else ToolRequest.Origin.ONLINE_MODEL
        val sourceTag = if (isLocal) "local" else "online:${provider.id}"
        val contextChars = if (isLocal) ((localContext ?: 4096) * 3) else 60_000

        val dialogueNote = dialogue(conversationId).describe()
        // Think twice on hard questions: a short plan first, then the answer, then a check.
        val thinkTwice = isLocal && !voice && s.thinkTwice && currentImage == null && com.melhive.zed.core.learning.HardQuestion.isHard(userText)
        val plan = if (!thinkTwice) null else {
            setPhase(AssistantPhase.THINKING, "Planning…", conversationId)
            val env = (environment ?: com.melhive.zed.core.understanding.Awareness(ZonedDateTime.now())).copy(profile = profile, memories = memories, computed = computed)
            generateText(provider, com.melhive.zed.core.learning.HardQuestion.PLAN_SYSTEM, env.render() + "\n\nQuestion: " + userText, 200, "think-$conversationId")
        }

        var lastText = ""
        for (round in 1..MAX_ROUNDS) {
            val history = conversations.history(conversationId, 40)
            val lastUser = history.lastOrNull { it.role == Role.USER }?.content.orEmpty()
            val knowledgeSnippets = if (s.useKnowledge && round == 1) {
                setPhase(AssistantPhase.SEARCHING, "documents", conversationId)
                runCatching { knowledge.search(lastUser, 3) }.getOrDefault(emptyList()).map { "${it.docPath}: ${it.text.take(if (isLocal) 400 else 700)}" }
            } else emptyList()
            val now = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd'T'HH:mm:ss (h:mm:ss a, VV)", java.util.Locale.ENGLISH))
            // On-device: keep the system prompt stable so the runtime can reuse its session; time and
            // document excerpts travel with the newest user message instead.
            val persona = com.melhive.zed.core.intelligence.Persona.parse(s.persona)
            // On-device: the system prompt never changes, so the runtime keeps its cached conversation;
            // the brief (time, place, memories, the tools that fit) travels with the message.
            val system = if (isLocal) PromptBuilder.localSystemPrompt(voice, persona, s.userTitle.ifBlank { null }) else PromptBuilder.systemPrompt(
                tools = toolSpecs,
                nowDescription = now,
                memories = memories,
                knowledge = knowledgeSnippets,
                voiceMode = voice,
                userName = userName,
                persona = persona,
                userTitle = s.userTitle.ifBlank { null },
            )
            val brief = if (!isLocal) null else (environment ?: com.melhive.zed.core.understanding.Awareness(ZonedDateTime.now())).let { env ->
                if (round == 1) env.copy(dialogue = dialogueNote, profile = profile, memories = memories, computed = computed, tools = toolSpecs, knowledge = knowledgeSnippets, plan = plan)
                else env.copy(tools = toolSpecs) // after a tool result: just the time and the tools
            }.render()
            val request = GenerationRequest(
                system = system,
                turns = ContextWindow.fit(history, contextChars),
                maxOutputTokens = when { isLocal && voice -> 256; isLocal -> 1024; voice -> 1024; else -> 4096 },
                sessionKey = "conversation-$conversationId",
                volatileContext = brief,
                images = listOfNotNull(currentImage),
            )
            val loading = isLocal && providers.localRuntime.state.value !is LocalModelRuntime.State.Ready
            setPhase(AssistantPhase.THINKING, if (loading) "Loading on-device model (first reply takes longer)…" else provider.label, conversationId)

            var result = stream(conversationId, provider, request, sourceTag)
            if (!result.ok && result.retryable) {
                // One automatic retry for transient failures (network blips, rate limits, 5xx).
                setPhase(AssistantPhase.THINKING, "Retrying…", conversationId)
                conversations.deleteMessage(result.messageId)
                delay(1500)
                result = stream(conversationId, provider, request, sourceTag)
            }
            if (!result.ok) return AssistantReply(result.text, false, sourceTag)

            val extraction = ToolCallExtractor.extract(result.text, origin)
            lastText = extraction.visibleText
            conversations.updateMessage(result.messageId, extraction.visibleText.ifBlank { if (extraction.requests.isNotEmpty()) "Working on it…" else "(no response)" }, "complete")
            if (extraction.requests.isEmpty()) {
                if (extraction.errors.isNotEmpty()) {
                    conversations.addMessage(conversationId, "tool", "Ignored malformed tool call(s): ${extraction.errors.joinToString("; ")}", source = "system")
                }
                if (thinkTwice && lastText.isNotBlank() && researchSources.isEmpty()) {
                    setPhase(AssistantPhase.THINKING, "Checking…", conversationId)
                    val check = generateText(provider, com.melhive.zed.core.learning.HardQuestion.CHECK_SYSTEM, com.melhive.zed.core.learning.HardQuestion.checkPrompt(userText, lastText), 500, "think-$conversationId")
                    com.melhive.zed.core.learning.HardQuestion.correction(check, lastText)?.let { fixed ->
                        lastText = fixed
                        conversations.updateMessage(result.messageId, fixed, "complete")
                    }
                }
                // Answers from the web always show where they came from.
                if (researchSources.isNotEmpty()) {
                    val withSources = com.melhive.zed.core.web.Research.withSources(lastText, researchSources)
                    if (withSources != lastText) { lastText = withSources; conversations.updateMessage(result.messageId, withSources, "complete") }
                }
                return AssistantReply(lastText.ifBlank { "(no response)" }, true, sourceTag)
            }
            for (req in extraction.requests) {
                val outcome = runTool(conversationId, req)
                val turn = PromptBuilder.toolResultTurn(req.tool, outcome.isSuccess(), outcome.message)
                conversations.addMessage(conversationId, "tool", turn.content, source = "tool", toolName = req.tool, status = "complete")
            }
            if (round == MAX_ROUNDS) break
        }
        val note = "I stopped after $MAX_ROUNDS tool rounds to stay safe. Ask me to continue if needed."
        conversations.addMessage(conversationId, "assistant", note, source = sourceTag)
        return AssistantReply(lastText.ifBlank { note }, true, sourceTag)
    }

    /** A short side generation (a plan, a check) that isn't shown as a chat message. Null when it fails. */
    private suspend fun generateText(provider: IntelligenceProvider, system: String, prompt: String, maxTokens: Int, session: String): String? {
        val out = StringBuilder()
        var failed = false
        provider.generate(GenerationRequest(system = system, turns = listOf(com.melhive.zed.core.intelligence.ChatTurn(Role.USER, prompt)), maxOutputTokens = maxTokens, sessionKey = session))
            .collect { e -> when (e) { is GenerationEvent.Delta -> out.append(e.text); is GenerationEvent.Error -> failed = true; else -> Unit } }
        return out.toString().trim().takeIf { it.isNotEmpty() && !failed }
    }

    private data class StreamResult(
        val messageId: Long, val text: String, val ok: Boolean, val retryable: Boolean = false,
        val calls: List<com.melhive.zed.intelligence.ToolCall> = emptyList(),
    )

    private suspend fun stream(conversationId: Long, provider: IntelligenceProvider, request: GenerationRequest, source: String): StreamResult {
        val id = conversations.addMessage(conversationId, "assistant", "", status = "streaming", source = source)
        val sb = StringBuilder()
        var lastFlush = 0L
        var error: GenerationEvent.Error? = null
        var first = true
        var firstTokenMs: Long? = null
        fun elapsedMs() = (System.nanoTime() - turnStartedAt) / 1_000_000
        val calls = mutableListOf<com.melhive.zed.intelligence.ToolCall>()
        try {
            provider.generate(request).collect { e ->
                when (e) {
                    is GenerationEvent.Delta -> {
                        if (first) { first = false; firstTokenMs = elapsedMs(); setPhase(AssistantPhase.THINKING, provider.label, conversationId) }
                        sb.append(e.text)
                        _streamingText.value = sb.toString()
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 120) { conversations.updateMessage(id, sb.toString(), "streaming"); lastFlush = now }
                    }
                    is GenerationEvent.Error -> error = e
                    is GenerationEvent.Usage -> _lastUsage.value = "${provider.label}: ${e.inputTokens ?: "?"} input / ${e.outputTokens ?: "?"} output tokens"
                    is GenerationEvent.Done -> Unit
                    is GenerationEvent.ToolCalls -> calls += e.calls
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { conversations.updateMessage(id, sb.toString().ifBlank { "(stopped)" }, "cancelled") }
            throw e
        }
        val err = error
        return if (err != null && sb.isBlank() && calls.isEmpty()) {
            conversations.updateMessage(id, err.message, "error")
            StreamResult(id, err.message, false, err.retryable)
        } else {
            if (err != null) sb.append("\n\n[Interrupted: ${err.message}]")
            conversations.updateMessage(id, sb.toString(), "complete")
            runCatching { conversations.setTiming(id, firstTokenMs, elapsedMs(), sb.length) }
            StreamResult(id, sb.toString(), true, calls = calls.toList())
        }
    }

    companion object {
        const val MAX_ROUNDS = 3
    }
}
