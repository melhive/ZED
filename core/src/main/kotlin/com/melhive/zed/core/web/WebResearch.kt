package com.melhive.zed.core.web

import com.melhive.zed.core.brain.BrainRecall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** One search result. */
data class SearchHit(val title: String, val url: String, val snippet: String) {
    val host: String get() = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty()
}

/** A page ZED read for an answer. */
data class WebPage(val title: String, val url: String, val text: String) {
    val host: String get() = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull().orEmpty()
}

/**
 * Reads the results pages of free, keyless search services. Each parser is
 * forgiving: it returns what it can find and an empty list when the page
 * changed shape, so the caller simply moves on to the next service.
 */
object SearchParsers {
    private val DOT = RegexOption.DOT_MATCHES_ALL

    /** DuckDuckGo's HTML endpoint (html.duckduckgo.com/html/?q=…). Ads are skipped. */
    fun duckDuckGo(html: String): List<SearchHit> {
        val blocks = html.split(Regex("<div class=\"result(?:s_links| )[^\"]*\"")).drop(1)
        return blocks.mapNotNull { b ->
            if (b.contains("result--ad") || b.contains("badge--ad")) return@mapNotNull null
            val a = Regex("<a[^>]*class=\"result__a\"[^>]*>(.*?)</a>", DOT).find(b) ?: return@mapNotNull null
            val href = Regex("href=\"([^\"]+)\"").find(a.value)?.groupValues?.get(1) ?: return@mapNotNull null
            val url = ddgTarget(Html.decode(href)) ?: return@mapNotNull null
            val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</(?:a|div|td)>", DOT).find(b)?.groupValues?.get(1).orEmpty()
            SearchHit(Html.text(a.groupValues[1]), url, Html.text(snippet))
        }.distinctBy { it.url }
    }

    /** "//duckduckgo.com/l/?uddg=https%3A%2F%2F…" → the real address; ad links → null. */
    private fun ddgTarget(href: String): String? {
        if (href.contains("duckduckgo.com/y.js")) return null
        Regex("[?&]uddg=([^&]+)").find(href)?.let { return URLDecoder.decode(it.groupValues[1], "UTF-8") }
        return href.takeIf { it.startsWith("http") }
    }

    /** Bing's results page (www.bing.com/search?q=…). */
    fun bing(html: String): List<SearchHit> {
        val blocks = html.split("<li class=\"b_algo\"").drop(1)
        return blocks.mapNotNull { b ->
            val a = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", DOT).find(b) ?: return@mapNotNull null
            val url = bingTarget(Html.decode(a.groupValues[1])) ?: return@mapNotNull null
            val snippet = Regex("<p[^>]*>(.*?)</p>", DOT).find(b)?.groupValues?.get(1).orEmpty()
            SearchHit(Html.text(a.groupValues[2]), url, Html.text(snippet))
        }.distinctBy { it.url }
    }

    /** Bing sometimes wraps links: …/ck/a?…&u=a1<base64url> → the real address. */
    private fun bingTarget(href: String): String? {
        if (!href.startsWith("http")) return null
        if (href.contains("bing.com/ck/a")) {
            val u = Regex("[?&]u=a1([^&]+)").find(href)?.groupValues?.get(1) ?: return null
            return runCatching { String(java.util.Base64.getUrlDecoder().decode(u.padEnd((u.length + 3) / 4 * 4, '='))) }.getOrNull()?.takeIf { it.startsWith("http") }
        }
        return href
    }

    /** Wikipedia's search API (…/w/api.php?action=query&list=search&format=json). */
    fun wikipedia(json: String, lang: String = "en"): List<SearchHit> = runCatching {
        val root = Json.parseToJsonElement(json) as JsonObject
        val list = ((root["query"] as? JsonObject)?.get("search") as? JsonArray).orEmpty()
        list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val snippet = (o["snippet"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            SearchHit(title, "https://$lang.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("%2F", "/"), Html.text(snippet))
        }
    }.getOrDefault(emptyList())

    fun duckDuckGoUrl(q: String) = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")
    fun bingUrl(q: String) = "https://www.bing.com/search?setlang=en&q=" + URLEncoder.encode(q, "UTF-8")
    fun wikipediaUrl(q: String) = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=" + URLEncoder.encode(q, "UTF-8")
}

/** Minimal HTML handling: entities, tags, and the readable text of a page. */
object Html {
    private val DOT = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "deg" to "°", "copy" to "©", "middot" to "·", "eacute" to "é")

    fun decode(s: String): String = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[e.lowercase()] ?: m.value
        }
    }

    /** Tags removed, entities decoded, whitespace collapsed. */
    fun text(html: String): String = decode(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    fun title(html: String): String = Regex("<title[^>]*>(.*?)</title>", DOT).find(html)?.groupValues?.get(1)?.let(::text).orEmpty()

    /**
     * The paragraphs of a page that matter for [question], in page order, up
     * to [maxChars]. Menus, scripts, footers and cookie banners are dropped.
     */
    fun readable(html: String, question: String, maxChars: Int = 1500): String {
        var h = html
        for (tag in listOf("script", "style", "noscript", "svg", "nav", "header", "footer", "aside", "form", "button", "select", "iframe", "template")) {
            h = Regex("<$tag\\b.*?</$tag>", DOT).replace(h, " ")
        }
        h = Regex("<!--.*?-->", DOT).replace(h, " ")
        var parts = Regex("<(p|li|h[1-3]|td|blockquote|dd)\\b[^>]*>(.*?)</\\1>", DOT).findAll(h).map { text(it.groupValues[2]) }
            .filter { it.length >= 40 && !COOKIE.containsMatchIn(it) }.toList()
        if (parts.isEmpty()) parts = text(Regex("<body\\b.*?</body>", DOT).find(h)?.value ?: h).chunked(400)
        val q = BrainRecall.keys(question)
        // Keep the best-matching paragraphs, then put them back in page order.
        val scored = parts.mapIndexed { i, p -> Triple(i, p, BrainRecall.keys(p).count { it in q } * 10 - i * 0.05 + if (i < 3) 1.0 else 0.0) }
        val chosen = mutableListOf<Pair<Int, String>>()
        var used = 0
        for ((i, p, _) in scored.sortedByDescending { it.third }) {
            if (used >= maxChars) break
            val piece = if (p.length > maxChars - used) p.take(maxChars - used).substringBeforeLast(' ') + "…" else p
            if (piece.length < 30) continue
            chosen += i to piece; used += piece.length + 1
        }
        return chosen.sortedBy { it.first }.joinToString("\n") { it.second }
    }

    private val COOKIE = Regex("\\b(cookies?|subscribe|sign up|log in|newsletter|accept all|privacy policy|javascript)\\b", RegexOption.IGNORE_CASE)
}

/** What to search, which results to read, and how the findings are put to the model. */
object Research {
    /** Sites that rarely hold a readable answer (videos, social feeds, app stores). */
    private val SKIP = Regex("(?:youtube\\.com|youtu\\.be|facebook\\.com|instagram\\.com|tiktok\\.com|pinterest\\.|twitter\\.com|x\\.com|linkedin\\.com|play\\.google\\.com|apps\\.apple\\.com|amazon\\.|ebay\\.|reddit\\.com/r/[^/]+/?$)", RegexOption.IGNORE_CASE)

    /**
     * Only the question itself leaves the phone: one line, no quotes or
     * bracketed context, at most 120 characters.
     */
    fun query(raw: String): String = raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        .replace(Regex("\\[[^]]*]"), " ").replace(Regex("[\"“”]"), "").replace(Regex("\\s+"), " ").trim().take(120).trim()

    /** Up to [n] results worth reading, from different sites. */
    fun pick(hits: List<SearchHit>, n: Int = 3): List<SearchHit> {
        val seen = HashSet<String>()
        return hits.filter { it.url.startsWith("http") && !SKIP.containsMatchIn(it.url) }
            .filter { seen.add(it.host) }
            .take(n)
    }

    /** The tool result Gemma answers from: numbered sources with their addresses and the passages read. */
    fun toolResult(question: String, pages: List<WebPage>): String = buildString {
        appendLine("Web sources for “$question” (cite them as [1], [2]…):")
        pages.forEachIndexed { i, p ->
            appendLine()
            appendLine("[${i + 1}] ${p.title.ifBlank { p.host }} — ${p.url}")
            appendLine(p.text.trim())
        }
        appendLine()
        append("Answer from these sources only and cite them like [1]. If they don't answer the question, say so plainly.")
    }

    /** "[1] Title — https://…" lines from a tool result, for the source list under an answer. */
    fun sourcesFrom(toolResult: String): List<String> =
        Regex("^\\[(\\d+)] (.+?) — (https?://\\S+)$", RegexOption.MULTILINE).findAll(toolResult).map { "[${it.groupValues[1]}] ${it.groupValues[2]} — ${it.groupValues[3]}" }.toList()

    /** Adds the source list under an answer that doesn't already show its addresses. */
    fun withSources(answer: String, sources: List<String>): String =
        if (sources.isEmpty() || answer.contains("http")) answer else answer.trimEnd() + "\n\nSources:\n" + sources.joinToString("\n")

    /** A tool result made readable for a person (no model to summarise it). */
    fun forPeople(toolResult: String): String = toolResult
        .substringBeforeLast("\n\nAnswer from these sources only")
        .replace(Regex("^Web sources for “(.+?)” \\(cite them as \\[1], \\[2]…\\):"), "Here's what I found about “$1”:")

    /** Short, readable answer when no model is available: the best passage from each source. */
    fun plainAnswer(question: String, pages: List<WebPage>): String = buildString {
        appendLine("Here's what I found about “$question”:")
        pages.forEachIndexed { i, p -> appendLine(); appendLine("[${i + 1}] " + p.text.lineSequence().firstOrNull().orEmpty().take(320)) }
        appendLine(); append("Sources:\n" + pages.mapIndexed { i, p -> "[${i + 1}] ${p.title.ifBlank { p.host }} — ${p.url}" }.joinToString("\n"))
    }
}
