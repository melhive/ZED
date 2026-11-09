package com.melhive.zenith.core.conversation

import kotlin.random.Random

/**
 * Conversational layer for when no AI model is available. It recognizes
 * everyday social intents and answers with varied, personalized replies.
 * It is honest about its limits: open-ended questions get a helpful
 * explanation of what ZENITH can do offline and how to enable a model.
 */
class SmallTalk(private val random: Random = Random.Default) {

    data class Context(
        val userName: String? = null,
        /** True when an AI provider exists but is currently unavailable (e.g. offline). */
        val modelConfiguredButUnavailable: Boolean = false,
        val hourOfDay: Int = 12,
    )

    private fun pick(vararg options: String) = options[random.nextInt(options.size)]
    private fun Context.name(prefix: String = ", ") = userName?.let { "$prefix$it" } ?: ""

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex("^(?:hey |ok |okay )?(?:zenith[,!]?\\s*)?(?:$p)(?:[,!]?\\s*zenith)?[.!?]*$", opt)

    private val greeting = rx("hi+|hello+|hey+|hiya|howdy|yo|greetings|good (?:afternoon|evening|day)|sup|what'?s up|wass?up")
    private val howAreYou = rx("how are you(?: doing| today)?|how'?s it going|how do you do|are you (?:ok|okay|alright)|how have you been")
    private val whoAreYou = rx("who are you|what are you|what(?:'s| is) your name|introduce yourself|tell me about yourself")
    private val whoMadeYou = rx("who (?:made|created|built|developed) you|where do you come from")
    private val thanks = rx("thanks?(?: you)?(?: so much| a lot| very much)?|thx|ty|cheers|much appreciated|appreciate it")
    private val bye = rx("bye+|goodbye|see you(?: later| soon)?|see ya|good ?night|later|talk to you later|ttyl|i'?m going to (?:sleep|bed)")
    private val joke = rx("tell me (?:a )?joke|say something funny|make me laugh|joke")
    private val praise = rx("(?:you(?:'re| are) )?(?:awesome|amazing|great|smart|cool|the best|brilliant|helpful)|good (?:job|work)|well done|nice|i love you|love you")
    private val insult = rx("(?:you(?:'re| are) )?(?:stupid|dumb|useless|bad|terrible|annoying|an idiot)|you suck")
    private val present = rx("are you (?:there|awake|listening|alive)|you there|hello\\?")
    private val bored = rx("i'?m bored|i am bored|bored")
    private val tired = rx("i'?m (?:so )?(?:tired|exhausted|sleepy)|i am (?:so )?(?:tired|exhausted|sleepy)")
    private val sad = rx("i'?m (?:so )?(?:sad|stressed|anxious|down|upset|lonely)|i am (?:so )?(?:sad|stressed|anxious|down|upset|lonely)|i feel (?:sad|stressed|anxious|down|bad|lonely)")
    private val happy = rx("i'?m (?:so )?(?:happy|excited|great|good|fine|well)|i am (?:so )?(?:happy|excited|great|good|fine|well)|i feel (?:great|good|happy)")
    private val myName = rx("what(?:'s| is) my name|do you know my name|who am i")
    private val ack = rx("ok(?:ay)?|cool|alright|got it|sure|yes|yeah|yep|no|nope|nah|fine|great|perfect|nice one|hmm+|i see")
    private val canYouThink = rx("can you think|are you (?:smart|intelligent|an ai|a robot|human|real)|do you have feelings")

    private val jokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I told my phone a joke about storage. It didn't get it — not enough space.",
        "Why did the smartphone need glasses? It lost all its contacts.",
        "There are 10 kinds of people: those who understand binary and those who don't.",
        "I'd tell you a UDP joke, but you might not get it.",
        "Why was the computer cold? It left its Windows open.",
        "My battery and I have a lot in common: we both need a recharge after lunch.",
    )

    private fun timeGreeting(h: Int) = when (h) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Hello" }

    /** Returns a reply for social messages, or null if this is not small talk. */
    fun respond(text: String, ctx: Context): String? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return when {
            greeting.matches(t) -> pick(
                "${timeGreeting(ctx.hourOfDay)}${ctx.name()}! What can I do for you?",
                "Hello${ctx.name()}. Ready when you are.",
                "Hi${ctx.name()}! Need a reminder, a quick calculation, or help with your files?",
            )
            howAreYou.matches(t) -> pick(
                "All systems nominal${ctx.name()}. How are you doing?",
                "Running smoothly, thanks for asking. What's on your mind?",
                "I'm doing well — fully charged and ready. How about you?",
            )
            whoAreYou.matches(t) -> "I'm ZENITH, your personal assistant on this phone. I can manage files, alarms, reminders, your task list and memory, do calculations and conversions, and run tasks in the background. " +
                if (ctx.modelConfiguredButUnavailable) "My AI model isn't reachable right now, so I'm in native mode." else "Add an on-device model or an online provider in Model Manager and I can hold open conversations too."
            whoMadeYou.matches(t) -> "I was built for you as a native Android app — Kotlin and Jetpack Compose, running locally on your phone."
            thanks.matches(t) -> pick("You're welcome${ctx.name()}.", "Anytime.", "Happy to help.", "Glad I could help${ctx.name()}.")
            bye.matches(t) -> if (Regex("night|sleep|bed", RegexOption.IGNORE_CASE).containsMatchIn(t)) pick("Good night${ctx.name()}. Want me to set an alarm first?", "Sleep well${ctx.name()}.")
                else pick("See you later${ctx.name()}.", "Goodbye — I'll be here when you need me.", "Talk soon${ctx.name()}.")
            joke.matches(t) -> jokes[random.nextInt(jokes.size)]
            praise.matches(t) -> pick("Thank you${ctx.name()} — that's kind.", "Appreciated. I'm here to make your day easier.", "Thanks! Tell me what's next.")
            insult.matches(t) -> pick("Sorry I fell short. Tell me what you expected and I'll try a different way.", "Fair enough — what went wrong? I'd like to fix it.")
            present.matches(t) -> pick("I'm here${ctx.name()}.", "Listening. What do you need?", "Right here.")
            bored.matches(t) -> pick("Want a joke? Or we could tidy your downloads — say “organize my downloads”.", "How about a quick task: “remind me to take a break in 25 minutes”?")
            tired.matches(t) -> pick("Maybe it's time for a break. I can set a timer: “set a timer for 20 minutes”.", "Rest is productive too${ctx.name()}. Want me to set an alarm?")
            sad.matches(t) -> pick(
                "I'm sorry you're feeling that way${ctx.name()}. Taking a short walk or talking to someone you trust can help. I'm here if you want to note anything down.",
                "That sounds hard. Be gentle with yourself — would a reminder to take a break help?",
            )
            happy.matches(t) -> pick("Great to hear${ctx.name()}!", "Love that. What shall we get done?", "Excellent. Keep the momentum going!")
            myName.matches(t) -> ctx.userName?.let { "You're $it." } ?: "I don't know your name yet. Tell me “my name is …” and I'll remember it."
            canYouThink.matches(t) -> "I'm software, not a person. Without a model I follow precise commands and calculations; with an on-device or online model I can reason and converse. I don't have feelings, but I do try to be useful."
            ack.matches(t) -> pick("Okay.", "Got it.", "Alright — anything else?", "Noted.")
            else -> null
        }
    }

    /** Reply for an open-ended message when no AI model can answer it. */
    fun fallback(text: String, ctx: Context): String {
        val lower = text.lowercase()
        val hint = when {
            Regex("remind|remember|forget").containsMatchIn(lower) -> "For reminders try “remind me to … at 5pm”; to save a fact say “remember that …”."
            Regex("file|folder|document|download").containsMatchIn(lower) -> "For files try “list files”, “create file notes.txt with content …” or “organize my downloads”."
            Regex("alarm|wake|timer").containsMatchIn(lower) -> "Try “set an alarm for 6:30 am” or “set a timer for 10 minutes”."
            Regex("meeting|event|calendar|schedule").containsMatchIn(lower) -> "Try “schedule meeting with Ana tomorrow at 3pm”."
            Regex("task|todo|to-do").containsMatchIn(lower) -> "Try “add task …”, “list tasks” or “complete …”."
            Regex("\\d").containsMatchIn(lower) -> "If that's a calculation, write it like “what is 18% of 250” or “convert 5 km to miles”."
            else -> "Say “help” to see everything I can do offline."
        }
        val why = if (ctx.modelConfiguredButUnavailable) "My AI model isn't available right now (check the connection or Model Manager), so I can't answer open-ended questions."
            else "Open-ended questions need an AI model, and none is set up yet. Download one in Model Manager (works offline) or add an online provider."
        return "$why $hint"
    }
}
