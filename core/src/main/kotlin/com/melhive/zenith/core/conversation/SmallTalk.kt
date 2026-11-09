package com.melhive.zenith.core.conversation

import kotlin.random.Random

/**
 * Conversational layer for when no AI model is available. It recognizes a
 * wide range of everyday intents, keeps a little conversational state (the
 * last topic) so follow-ups like "and you?", "another one" or "why?" work,
 * varies its wording, personalizes with the user's name, and offers small
 * deterministic fun (jokes, facts, coin flips, dice, random numbers,
 * recommendations). It stays honest: open-ended questions get a helpful
 * explanation of what works offline and how to enable a model.
 */
class SmallTalk(private val random: Random = Random.Default) {

    data class Context(
        val userName: String? = null,
        /** True when an AI provider exists but is currently unavailable (e.g. offline). */
        val modelConfiguredButUnavailable: Boolean = false,
        val hourOfDay: Int = 12,
    )

    private enum class Topic { NONE, GREETING, HOW_ARE_YOU, JOKE, FACT, QUOTE, RIDDLE, FEELING_BAD, FEELING_GOOD, IDENTITY, RECOMMEND }

    private var lastTopic = Topic.NONE
    private var pendingRiddleAnswer: String? = null
    private var lastIndex = mutableMapOf<String, Int>()

    private fun pick(vararg options: String) = options[random.nextInt(options.size)]

    /** Picks from a list without repeating the previous choice. */
    private fun rotate(key: String, list: List<String>): String {
        var i = random.nextInt(list.size)
        if (list.size > 1 && i == lastIndex[key]) i = (i + 1) % list.size
        lastIndex[key] = i
        return list[i]
    }

    private fun Context.name(prefix: String = ", ") = userName?.let { "$prefix$it" } ?: ""

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex("^(?:hey |oh |ok |okay |well |so |um+ )?(?:zenith[,!]?\\s*)?(?:$p)(?:[,!]?\\s*(?:zenith|buddy|friend|mate|bro))?[.!?]*$", opt)

    private val greeting = rx("hi+|hello+|hey+|hiya|howdy|yo|greetings|good (?:morning|afternoon|evening|day)|morning|evening|sup|what'?s up|wass?up|hey there|hi there|hello there")
    private val howAreYou = rx("how are you(?: doing| today| feeling)?|how'?s it going|how do you do|are you (?:ok|okay|alright|good)|how have you been|how'?s your day(?: going)?|how are things")
    private val andYou = rx("and you|what about you|how about you|you\\?|and yourself|wbu|hbu")
    private val whatDoing = rx("what are you doing|what'?re you up to|what are you up to|wyd|busy\\?|are you busy")
    private val whoAreYou = rx("who are you|what are you|what(?:'s| is) your name|introduce yourself|tell me about yourself|what do you do")
    private val whoMadeYou = rx("who (?:made|created|built|developed|programmed) you|where do you come from|where are you from")
    private val age = rx("how old are you|when were you born|what(?:'s| is) your age")
    private val real = rx("are you (?:real|human|alive|a person|a robot|an ai|a bot|sentient|conscious)|can you think|do you have feelings|do you have emotions")
    private val sleep = rx("do you (?:ever )?sleep|do you get tired|do you eat|do you dream")
    private val likes = Regex("^do you (?:like|love|enjoy) (.+?)[?.!]*$", opt)
    private val favorite = Regex("^what(?:'s| is) your fav(?:ou?rite)? (.+?)[?.!]*$", opt)
    private val thanks = rx("thanks?(?: you)?(?: so much| a lot| very much)?|thx|ty|cheers|much appreciated|appreciate it|thank u")
    private val sorry = rx("sorry|my bad|i'?m sorry|apologies|oops")
    private val bye = rx("bye+|goodbye|see you(?: later| soon| tomorrow)?|see ya|good ?night|nite|later|talk to you later|ttyl|i'?m going to (?:sleep|bed)|gotta go|i have to go")
    private val laugh = rx("(?:ha)+h?|lol+|lmao|rofl|hehe+|that'?s funny|funny|good one|nice one")
    private val joke = rx("tell me (?:a |another )?joke|say something funny|make me laugh|joke|another joke|tell me something funny")
    private val another = rx("another(?: one)?|one more|more|again|tell me another|next")
    private val fact = rx("tell me (?:a |an )?(?:fun |interesting |random )?fact|fun fact|did you know|teach me something|tell me something (?:interesting|cool)")
    private val quote = rx("motivate me|inspire me|i need motivation|give me (?:a )?(?:quote|motivation)|motivational quote|quote")
    private val riddle = rx("tell me a riddle|riddle|give me a riddle|ask me a riddle")
    private val why = rx("why|why\\?|how come|really|seriously|are you sure|is that true")
    private val praise = rx("(?:you(?:'re| are) )?(?:so )?(?:awesome|amazing|great|smart|cool|the best|brilliant|helpful|clever|funny|nice|sweet)|good (?:job|work|bot)|well done|i like you|i love you|love you|you rock")
    private val insult = rx("(?:you(?:'re| are) )?(?:so )?(?:stupid|dumb|useless|bad|terrible|annoying|an idiot|boring|slow)|you suck|shut up|i hate you")
    private val present = rx("are you (?:there|awake|listening|alive|here)|you there|hello\\?|anybody there")
    private val bored = rx("i'?m (?:so )?bored|i am (?:so )?bored|bored|i have nothing to do")
    private val tired = rx("i'?m (?:so |really )?(?:tired|exhausted|sleepy)|i am (?:so |really )?(?:tired|exhausted|sleepy)")
    private val sad = rx("i'?m (?:so |really |feeling )?(?:sad|stressed|anxious|down|upset|lonely|depressed|worried|overwhelmed)|i am (?:so |really |feeling )?(?:sad|stressed|anxious|down|upset|lonely|depressed|worried|overwhelmed)|i feel (?:sad|stressed|anxious|down|bad|lonely|terrible|awful|overwhelmed)|(?:bad|rough|terrible) day")
    private val happy = rx("i'?m (?:so |really |feeling )?(?:happy|excited|great|good|fine|well|okay|ok)|i am (?:so |really )?(?:happy|excited|great|good|fine|well)|i feel (?:great|good|happy|fine)|(?:good|great|awesome) day|not bad|pretty good|doing (?:good|well|great|fine)")
    private val hungry = rx("i'?m (?:so )?(?:hungry|starving)|i am hungry|what should i eat|what(?:'s| is) for (?:lunch|dinner|breakfast)")
    private val recommend = Regex("^(?:recommend|suggest)(?: me)? (?:a |an |some )?(movie|film|book|song|music|show|series|game|food|dish|meal|recipe)s?[?.!]*$|^(?:any |what )(movie|film|book|song|show|game)s? (?:should i|to) (?:watch|read|listen to|play)[?.!]*$", opt)
    private val myName = rx("what(?:'s| is) my name|do you know my name|who am i|do you remember me")
    private val canHelp = rx("can you help(?: me)?|i need help|help me|can i ask you something|i have a question")
    private val weather = Regex("\\b(weather|forecast|temperature outside|is it (?:going to )?rain|will it rain)\\b", opt)
    private val news = Regex("\\b(news|headlines|stock price|bitcoin price|score of|who won)\\b", opt)
    private val coin = rx("flip a coin|toss a coin|heads or tails|coin flip")
    private val dice = Regex("^(?:roll (?:a |the )?(?:die|dice)|roll a d(\\d+)|roll (\\d+) dice)[?.!]*$", opt)
    private val randomNum = Regex("^(?:pick|give me|choose|generate) (?:a )?random number(?: between (-?\\d+) and (-?\\d+)| from (-?\\d+) to (-?\\d+))?[?.!]*$", opt)
    private val decide = Regex("^(?:should i (.+?)|yes or no|decide for me|help me decide)[?.!]*$", opt)
    private val ack = rx("ok(?:ay)?|k|cool|alright|got it|sure|yes|yeah|yep|yup|no|nope|nah|fine|great|perfect|hmm+|i see|right|true|exactly|makes sense|interesting|wow|oh|nice|awesome")

    private val jokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I told my phone a joke about storage. It didn't get it — not enough space.",
        "Why did the smartphone need glasses? It lost all its contacts.",
        "There are 10 kinds of people: those who understand binary and those who don't.",
        "Why was the computer cold? It left its Windows open.",
        "My battery and I have a lot in common: we both need a recharge after lunch.",
        "Why don't scientists trust atoms? Because they make up everything.",
        "I would tell you a construction joke, but I'm still working on it.",
        "What do you call a fake noodle? An impasta.",
        "Why did the scarecrow win an award? He was outstanding in his field.",
        "Parallel lines have so much in common. It's a shame they'll never meet.",
        "I'm reading a book about anti-gravity. It's impossible to put down.",
    )
    private val facts = listOf(
        "Honey never spoils — archaeologists have found 3,000-year-old honey that was still edible.",
        "Octopuses have three hearts and blue blood.",
        "A day on Venus is longer than its year.",
        "Bananas are berries, but strawberries aren't.",
        "Sharks existed before trees.",
        "The Eiffel Tower can grow about 15 cm taller in summer as the metal expands.",
        "Wombat poop is cube-shaped.",
        "There are more possible chess games than atoms in the observable universe.",
        "Your brain uses about 20% of your body's energy.",
        "The first computer bug was an actual moth found in a relay in 1947.",
        "A group of flamingos is called a flamboyance.",
        "Hot water can freeze faster than cold water under some conditions — the Mpemba effect.",
    )
    private val quotes = listOf(
        "“The secret of getting ahead is getting started.” — Mark Twain",
        "“It always seems impossible until it's done.” — Nelson Mandela",
        "“Small daily improvements are the key to staggering long-term results.”",
        "“You don't have to be great to start, but you have to start to be great.” — Zig Ziglar",
        "“Discipline is choosing between what you want now and what you want most.”",
        "“Done is better than perfect.”",
        "“Action is the foundational key to all success.” — Pablo Picasso",
    )
    private val riddles = listOf(
        "What has keys but can't open locks?" to "A piano.",
        "What gets wetter the more it dries?" to "A towel.",
        "What has hands but can't clap?" to "A clock.",
        "The more you take, the more you leave behind. What am I?" to "Footsteps.",
        "What can travel around the world while staying in a corner?" to "A stamp.",
        "What has a head and a tail but no body?" to "A coin.",
    )
    private val recs = mapOf(
        "movie" to listOf("Interstellar — big ideas and a great score.", "The Martian — clever, funny survival.", "Spirited Away — beautiful and timeless.", "Knives Out — a sharp, fun mystery.", "Arrival — thoughtful sci-fi about language."),
        "book" to listOf("Atomic Habits by James Clear — practical and motivating.", "Project Hail Mary by Andy Weir — fun sci-fi.", "Sapiens by Yuval Noah Harari — a big-picture history.", "The Little Prince — short and wise.", "Deep Work by Cal Newport — on focus."),
        "song" to listOf("“Bohemian Rhapsody” by Queen.", "“Here Comes the Sun” by The Beatles.", "“Blinding Lights” by The Weeknd.", "“Clair de Lune” by Debussy for something calm."),
        "show" to listOf("Planet Earth II — stunning nature.", "Ted Lasso — warm and funny.", "Breaking Bad — gripping drama.", "Avatar: The Last Airbender — great at any age."),
        "game" to listOf("Stardew Valley — relaxing farming.", "Chess — on any chess app; endlessly deep.", "Monument Valley — beautiful puzzles on mobile.", "Wordle-style daily puzzles — quick and fun."),
        "food" to listOf("A warm bowl of chicken adobo with rice.", "Pasta aglio e olio — 15 minutes, few ingredients.", "A big salad with grilled chicken and avocado.", "Ramen with a soft-boiled egg.", "Tacos — easy to customize."),
    )

    private fun timeGreeting(h: Int) = when (h) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Hi" }

    /** Returns a reply for social messages, or null if this is not small talk. */
    fun respond(text: String, ctx: Context): String? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        val reply = answer(t, ctx)
        return reply
    }

    private fun answer(t: String, ctx: Context): String? {
        // Riddle answers
        pendingRiddleAnswer?.let { ans ->
            pendingRiddleAnswer = null
            val key = ans.lowercase().removePrefix("a ").removeSuffix(".")
            return if (t.lowercase().contains(key)) "Correct — ${ans.lowercase().removeSuffix(".")}! Nicely done${ctx.name()}. Want another?"
            else if (Regex("give up|i don'?t know|no idea|tell me|idk", opt).containsMatchIn(t)) "It's ${ans.lowercase().removeSuffix(".")} Want another one?"
            else "Not quite — it's ${ans.lowercase().removeSuffix(".")} Want another?"
        }
        return when {
            greeting.matches(t) -> { lastTopic = Topic.GREETING; pick(
                "${timeGreeting(ctx.hourOfDay)}${ctx.name()}! How's your day going?",
                "Hello${ctx.name()}! What's on your mind?",
                "Hey${ctx.name()}! Good to see you. What can I do for you?",
                "Hi${ctx.name()} — ready when you are.",
            ) }
            howAreYou.matches(t) -> { lastTopic = Topic.HOW_ARE_YOU; pick(
                "I'm doing great, thanks for asking${ctx.name()}! How about you?",
                "All systems running smoothly. How are you doing?",
                "Pretty good — fully charged and ready to help. How's your day?",
            ) }
            andYou.matches(t) -> when (lastTopic) {
                Topic.HOW_ARE_YOU, Topic.GREETING, Topic.FEELING_GOOD, Topic.FEELING_BAD -> pick("I'm doing well, thanks for asking! Anything I can help you with?", "I'm good — happy to be chatting with you.")
                else -> pick("Me? I'm just happy to help.", "I'm doing fine, thanks!")
            }
            whatDoing.matches(t) -> pick("Just here, keeping an eye on your reminders and tasks. What are you up to?", "Waiting for your next idea. What's up?", "Organizing bits and bytes. How about you?")
            whoAreYou.matches(t) -> { lastTopic = Topic.IDENTITY; "I'm ZENITH, your personal assistant on this phone. I can chat, set reminders and alarms, manage files and your task list, remember things for you, and do quick math and conversions. " +
                if (ctx.modelConfiguredButUnavailable) "My AI model isn't reachable right now, so I'm in my lighter native mode." else "With an AI model installed I can hold much deeper conversations." }
            whoMadeYou.matches(t) -> "I was built as a native Android app — Kotlin and Jetpack Compose — and I run right here on your phone."
            age.matches(t) -> "I'm quite new — born in November 2026. Young, but learning fast."
            real.matches(t) -> "I'm software, not a person — no feelings, but I do care about being useful to you. In native mode I follow precise rules; with an AI model I can reason and converse more freely."
            sleep.matches(t) -> pick("No sleep for me — but I'll happily remind you to get some.", "I don't eat or sleep, but I do enjoy a fully charged battery.")
            likes.matches(t) -> { val thing = likes.find(t)!!.groupValues[1]; pick("I don't have tastes like you do, but $thing sounds like a good choice. Do you like it?", "If I could, I think I'd enjoy $thing. What do you like about it?") }
            favorite.matches(t) -> { val thing = favorite.find(t)!!.groupValues[1].lowercase()
                when {
                    thing.contains("color") || thing.contains("colour") -> "Cyan — it's the color of my orb. What's yours?"
                    thing.contains("food") -> "I can't eat, but adobo gets a lot of love. What's yours?"
                    thing.contains("movie") -> "Interstellar, if I had to pick. Yours?"
                    thing.contains("number") -> "42 — it's the answer to everything, apparently."
                    else -> "I don't really have a favorite $thing. What's yours?"
                } }
            thanks.matches(t) -> pick("You're welcome${ctx.name()}!", "Anytime.", "Happy to help!", "My pleasure${ctx.name()}.")
            sorry.matches(t) -> pick("No worries at all.", "All good${ctx.name()}!", "Nothing to apologize for.")
            bye.matches(t) -> if (Regex("night|sleep|bed|nite", opt).containsMatchIn(t)) pick("Good night${ctx.name()}! Want me to set an alarm first?", "Sleep well${ctx.name()}. Talk tomorrow!")
                else pick("See you later${ctx.name()}!", "Bye for now — I'm here whenever you need me.", "Take care${ctx.name()}!")
            laugh.matches(t) -> pick("Glad that landed! 😄", "Ha — I'm here all week.", "I'll take that as a win.")
            joke.matches(t) -> { lastTopic = Topic.JOKE; rotate("joke", jokes) }
            fact.matches(t) -> { lastTopic = Topic.FACT; "Here's one: " + rotate("fact", facts) }
            quote.matches(t) -> { lastTopic = Topic.QUOTE; rotate("quote", quotes) + if (ctx.userName != null) " You've got this, ${ctx.userName}." else " You've got this." }
            riddle.matches(t) -> { lastTopic = Topic.RIDDLE; val (q, a) = riddles[random.nextInt(riddles.size)]; pendingRiddleAnswer = a; "$q (Take a guess, or say “I give up”.)" }
            another.matches(t) -> when (lastTopic) {
                Topic.JOKE -> rotate("joke", jokes)
                Topic.FACT -> "Another one: " + rotate("fact", facts)
                Topic.QUOTE -> rotate("quote", quotes)
                Topic.RIDDLE -> { val (q, a) = riddles[random.nextInt(riddles.size)]; pendingRiddleAnswer = a; q }
                else -> null
            }
            why.matches(t) -> when (lastTopic) {
                Topic.FACT -> "It's genuinely true — a surprising one, right? With an AI model installed I could explain the details."
                Topic.JOKE -> "Because puns are the highest form of humor. Obviously."
                else -> null
            }
            praise.matches(t) -> pick("Thank you${ctx.name()} — that made my day.", "Aw, thanks! You're pretty great yourself.", "Appreciated! What shall we do next?")
            insult.matches(t) -> pick("Sorry I let you down. Tell me what you expected and I'll try to do better.", "Fair — I'm not perfect. What went wrong?")
            present.matches(t) -> pick("I'm here${ctx.name()}!", "Right here. What do you need?", "Listening.")
            bored.matches(t) -> pick("Let's fix that. Want a fun fact, a riddle or a joke?", "How about a riddle? Say “tell me a riddle”.", "We could tidy up your downloads — say “organize my downloads”. Or I can tell you a fun fact.")
            tired.matches(t) -> pick("Sounds like you need a break. Want me to set a 20-minute timer for a power nap?", "Rest is productive too${ctx.name()}. Should I set an alarm?")
            sad.matches(t) -> { lastTopic = Topic.FEELING_BAD; pick(
                "I'm sorry you're going through that${ctx.name()}. Do you want to talk about it? Sometimes a short walk, some water, or messaging someone you trust helps a little.",
                "That sounds hard. Be gentle with yourself today. Is there one small thing I can take off your plate — a reminder or a task?",
                "I'm here for you. Take a slow breath. Want me to remind you to take a break in a bit?",
            ) }
            happy.matches(t) -> { lastTopic = Topic.FEELING_GOOD; pick("Love to hear that${ctx.name()}! What's making it a good day?", "That's great! Anything I can help you get done while you're on a roll?", "Awesome. Keep that energy going!") }
            hungry.matches(t) -> { lastTopic = Topic.RECOMMEND; "How about " + rotate("food", recs.getValue("food")).replaceFirstChar { it.lowercase() } + " Want another idea?" }
            recommend.matches(t) -> { lastTopic = Topic.RECOMMEND
                val m = recommend.find(t)!!
                val kind = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase().let { when (it) { "film" -> "movie"; "music" -> "song"; "series" -> "show"; "dish", "meal", "recipe" -> "food"; else -> it } }
                "Try " + rotate(kind, recs[kind] ?: recs.getValue("movie")).replaceFirstChar { it.lowercase() } }
            myName.matches(t) -> ctx.userName?.let { "Of course — you're $it." } ?: "I don't know your name yet. Tell me “my name is …” and I'll remember it."
            canHelp.matches(t) -> pick("Of course${ctx.name()}. What do you need?", "Sure — go ahead and ask.", "Absolutely. What's up?")
            weather.containsMatchIn(t) -> "I can't check live weather without an online AI provider. If you add one in Model Manager, I can research it for you."
            news.containsMatchIn(t) -> "I can't get live news or prices offline. With an online AI provider configured, say “research …” and I'll look it up."
            coin.matches(t) -> "It's ${if (random.nextBoolean()) "heads" else "tails"}!"
            dice.matches(t) -> { val m = dice.find(t)!!
                val sides = m.groupValues[1].toIntOrNull()?.coerceIn(2, 1000) ?: 6
                val count = m.groupValues[2].toIntOrNull()?.coerceIn(1, 20) ?: 1
                val rolls = List(count) { random.nextInt(1, sides + 1) }
                if (count == 1) "You rolled a ${rolls[0]}." else "You rolled ${rolls.joinToString(", ")} — total ${rolls.sum()}." }
            randomNum.matches(t) -> { val m = randomNum.find(t)!!
                val a = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: 1
                val b = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: 100
                val (lo, hi) = if (a <= b) a to b else b to a
                "${random.nextInt(lo, hi + 1)}." }
            decide.matches(t) -> pick("I'd say yes — go for it.", "Hmm… not this time.", "Yes, but sleep on it first.", "Flip a coin — and notice which side you're hoping for. That's your answer.")
            ack.matches(t) -> when (lastTopic) {
                Topic.JOKE -> pick("Want another one?", "I've got more where that came from.")
                Topic.FACT -> pick("Want another fact?", "Pretty wild, right?")
                else -> pick("Okay!", "Got it.", "Alright — anything else?", "Sounds good.")
            }
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
            else -> "Meanwhile I can chat, tell jokes and facts, do math and conversions, and handle reminders, tasks and files — say “help” for the full list."
        }
        val why = if (ctx.modelConfiguredButUnavailable) "That needs my AI model, which isn't available right now (check the connection or Model Manager)."
            else pick("That's a great question, but answering it properly needs an AI model, and none is set up yet. You can add one in a tap from Home (it works offline).",
                "I'd love to dig into that, but open questions need an AI model. Download one from Home or Model Manager and ask me again.")
        return "$why $hint"
    }
}
