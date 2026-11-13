package com.melhive.zed.core.understanding

/**
 * Decides when a message needs the internet, and what to search for. When it does and the phone
 * is online, ZED searches first and answers from the results; offline, it answers from what the
 * model knows and says it couldn't check online.
 */
object WebNeed {
    private val opt = setOf(RegexOption.IGNORE_CASE)

    /** "search the web for …", "google …", "look it up", "check online". */
    private val EXPLICIT = Regex("^(?:(?:hey )?zed,? )?(?:please |can you |could you |would you )?(?:search|look up|google|check|find|research)(?: on| in)?(?: the)? (?:web|internet|online|google)(?: for| about)?\\s*(.*)$|^(?:(?:hey )?zed,? )?(?:please |can you |could you )?(?:google|look up|research)\\s+(.+)$|^(?:(?:hey )?zed,? )?(?:please |can you |could you )?(?:look|search) (?:it|that|this) up(?: online)?$|\\b(?:search|check|look) (?:it |that |this )?(?:on|in) (?:the )?(?:web|internet|google)\\b", opt)
    /** Things that change: news, prices, results, schedules, "right now". */
    private val FRESH = Regex("\\b(?:latest|news|today'?s|tonight'?s|current(?:ly)?|right now|this (?:week|month|year|season)|recent(?:ly)?|prices?|cost|how much (?:is|are|does)|exchange rate|stock|score|who won|winner|results?|released?|release date|trending|update|happening|open now|opening hours|schedule of|when (?:is|does|will) .+ (?:open|start|come out|release|air))\\b", opt)
    /** Questions about the world. */
    private val ABOUT = Regex("^(?:(?:hey )?zed,? )?(?:can you |could you |please )?(?:tell me (?:about|more about)|what do you know about|give me (?:info|information|details) (?:about|on)|who (?:is|was|are|were)|what (?:is|are|was|were) (?:the |a |an )?(?:[\\p{L}\\d'-]+ ){0,6}[\\p{L}\\d'-]+\\??$|where (?:is|are)|when (?:is|was|did|does|will)|how (?:many|much|old|tall|far|big|long) (?:is|are|was|does|did|people)|what happened)\\b", opt)
    /** About the user, the phone or ZED itself: never a web search. */
    private val PERSONAL = Regex("\\b(?:my|mine|our|your(?:self)?|reminders?|alarms?|tasks?|calendar|schedule|battery|phone|zed)\\b|^(?:how are you|who are you|what are you|what is your|what'?s your|tell me about (?:you|yourself|me|myself))", opt)
    /** Phrases stripped to get the search terms. */
    private val LEAD = Regex("^(?:(?:hey )?zed,? )?(?:please |can you |could you |would you )?(?:tell me (?:more )?about|what do you know about|give me (?:info|information|details) (?:about|on)|search (?:the )?(?:web|internet|online)(?: for| about)?|look up|google|research)\\s+", opt)

    sealed interface Need {
        /** Search for [query]. [explicit]: the user asked for a search. */
        data class Search(val query: String, val explicit: Boolean) : Need
        /** The user asked whether ZED can search ("can you search the internet?"). */
        data object AskedAboutSearching : Need
    }

    fun classify(text: String, previous: String? = null): Need? {
        val t = text.trim().trimEnd('.', '!')
        if (t.isEmpty()) return null
        if (Regex("^(?:(?:hey )?zed,? )?(?:can|do|could) you (?:search|browse|use|access|look things up on|go on) (?:the )?(?:web|internet|online|google)(?: for me)?\\??$|^is there (?:a|another) way (?:for you |you can )?(?:to )?search (?:the )?(?:web|internet)", opt).containsMatchIn(t))
            return Need.AskedAboutSearching
        EXPLICIT.find(t)?.let { m ->
            val topic = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()?.trimEnd('?')
            val query = when {
                !topic.isNullOrBlank() -> topic
                // "look it up": the subject is the previous question.
                previous != null -> previous.trim()
                else -> LEAD.replace(t, "").trimEnd('?')
            }
            if (query.length >= 2) return Need.Search(query.take(160), explicit = true)
        }
        if (PERSONAL.containsMatchIn(t)) return null
        val words = t.split(Regex("\\s+")).size
        if (words < 2) return null
        if (FRESH.containsMatchIn(t) || ABOUT.containsMatchIn(t)) return Need.Search(LEAD.replace(t, "").trimEnd('?').take(160), explicit = false)
        return null
    }
}
