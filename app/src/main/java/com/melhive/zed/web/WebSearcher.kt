package com.melhive.zed.web

import com.melhive.zed.core.web.Html
import com.melhive.zed.core.web.Research
import com.melhive.zed.core.web.SearchHit
import com.melhive.zed.core.web.SearchParsers
import com.melhive.zed.core.web.WebPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Searches the web without any account or key: DuckDuckGo first, then Bing,
 * then Wikipedia, and reads the best few pages.
 *
 * Privacy: the only thing sent is the search question (see [Research.query])
 * to the search service, and plain page requests to the result sites. No
 * cookies, no account, nothing from ZED's memory or conversations.
 */
class WebSearcher(private val client: OkHttpClient = DEFAULT_CLIENT) {

    data class Findings(val query: String, val pages: List<WebPage>, val service: String)

    class Unavailable(message: String) : IOException(message)

    /** Searches and reads up to [pages] results. Throws [Unavailable] with a plain explanation when every service fails. */
    suspend fun research(question: String, pages: Int = 3): Findings = withContext(Dispatchers.IO) {
        val q = Research.query(question)
        if (q.isBlank()) throw Unavailable("There was nothing to search for.")
        val (hits, service) = search(q)
        val chosen = Research.pick(hits, pages + 2)
        // Read pages in parallel; slow sites are dropped rather than waited for.
        val read = coroutineScope {
            chosen.map { hit -> async { withTimeoutOrNull(9_000) { runCatching { read(hit, q) }.getOrNull() } } }.awaitAll()
        }.filterNotNull().filter { it.text.length >= 80 }.take(pages)
        // Pages that wouldn't load still leave the search snippets, which often answer simple questions.
        val result = read.ifEmpty { chosen.filter { it.snippet.length >= 40 }.take(pages).map { WebPage(it.title, it.url, it.snippet) } }
        if (result.isEmpty()) throw Unavailable("I searched for “$q” but couldn't read any of the results. Try asking in a different way.")
        Findings(q, result, service)
    }

    private fun search(q: String): Pair<List<SearchHit>, String> {
        val failures = mutableListOf<String>()
        val services: List<Triple<String, String, (String) -> List<SearchHit>>> = listOf(
            Triple("DuckDuckGo", SearchParsers.duckDuckGoUrl(q)) { SearchParsers.duckDuckGo(it) },
            Triple("Bing", SearchParsers.bingUrl(q)) { SearchParsers.bing(it) },
            Triple("Wikipedia", SearchParsers.wikipediaUrl(q)) { SearchParsers.wikipedia(it) },
        )
        for ((name, url, parse) in services) {
            val hits = try { parse(get(url)) } catch (e: Exception) { failures += "$name: ${e.message ?: e.javaClass.simpleName}"; continue }
            if (hits.isNotEmpty()) return hits to name
            failures += "$name: no results"
        }
        throw Unavailable("I couldn't search the web right now — DuckDuckGo, Bing and Wikipedia all failed (${failures.joinToString("; ")}). Try again in a minute.")
    }

    private fun read(hit: SearchHit, question: String): WebPage {
        val html = get(hit.url, maxBytes = 900_000)
        val text = Html.readable(html, question, maxChars = 1300)
        return WebPage(hit.title.ifBlank { Html.title(html) }, hit.url, text.ifBlank { hit.snippet })
    }

    private fun get(url: String, maxBytes: Long = 1_500_000): String {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.8")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val type = resp.header("Content-Type").orEmpty()
            if (type.isNotEmpty() && !type.contains("html") && !type.contains("json") && !type.contains("text")) throw IOException("not a web page ($type)")
            val source = resp.body?.source() ?: throw IOException("empty response")
            source.request(maxBytes)
            return source.buffer.snapshot(minOf(source.buffer.size, maxBytes).toInt()).utf8()
        }
    }

    companion object {
        /** A common browser identity, so search pages answer as they would for anyone. Nothing identifies the user. */
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        val DEFAULT_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
