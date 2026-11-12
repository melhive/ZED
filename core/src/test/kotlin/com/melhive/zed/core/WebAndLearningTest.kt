package com.melhive.zed.core

import com.melhive.zed.core.learning.FactPatterns
import com.melhive.zed.core.learning.HardQuestion
import com.melhive.zed.core.learning.LearnedFact
import com.melhive.zed.core.learning.LearnedFilter
import com.melhive.zed.core.learning.LearningPrompt
import com.melhive.zed.core.understanding.ToolSelector
import com.melhive.zed.core.web.Html
import com.melhive.zed.core.web.Research
import com.melhive.zed.core.web.SearchHit
import com.melhive.zed.core.web.SearchParsers
import com.melhive.zed.core.web.WebPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebResearchTest {
    private val ddg = """
        <div class="results">
        <div class="result results_links results_links_deep result--ad ">
          <h2 class="result__title"><a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad_domain=shop.com">Buy now</a></h2>
          <a class="result__snippet" href="#">Sponsored</a>
        </div>
        <div class="result results_links results_links_deep web-result ">
          <div class="links_main links_deep result__body">
            <h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.pagasa.dost.gov.ph%2Fweather&amp;rut=abc">PAGASA <b>Weather</b> Forecast</a></h2>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Official <b>forecast</b> for the Philippines &amp; typhoon updates.</a>
          </div>
        </div>
        <div class="result results_links results_links_deep web-result ">
          <div class="links_main links_deep result__body">
            <h2 class="result__title"><a class="result__a" rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FTyphoon">Typhoon - Wikipedia</a></h2>
            <a class="result__snippet">A typhoon is a mature tropical cyclone.</a>
          </div>
        </div>
        </div>
    """.trimIndent()

    @Test fun duckDuckGoResultsWithoutAds() {
        val hits = SearchParsers.duckDuckGo(ddg)
        assertEquals(2, hits.size)
        assertEquals(SearchHit("PAGASA Weather Forecast", "https://www.pagasa.dost.gov.ph/weather", "Official forecast for the Philippines & typhoon updates."), hits[0])
        assertEquals("en.wikipedia.org", hits[1].host)
    }

    @Test fun bingResultsIncludingWrappedLinks() {
        val wrapped = "https://www.bing.com/ck/a?!&&p=abc&u=a1" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://example.org/page".toByteArray()) + "&ntb=1"
        val html = """
            <ol id="b_results">
            <li class="b_algo" data-id=""><h2><a href="https://www.gsmarena.com/best_phones.php" h="ID=1">Best <strong>phones</strong> 2026</a></h2>
              <div class="b_caption"><p class="b_lineclamp2">Our pick of the best phones you can buy.</p></div></li>
            <li class="b_algo"><h2><a href="${wrapped.replace("&", "&amp;")}">Example</a></h2><p>Second result.</p></li>
            </ol>
        """.trimIndent()
        val hits = SearchParsers.bing(html)
        assertEquals(listOf("https://www.gsmarena.com/best_phones.php", "https://example.org/page"), hits.map { it.url })
        assertEquals("Best phones 2026", hits[0].title)
        assertEquals("Our pick of the best phones you can buy.", hits[0].snippet)
    }

    @Test fun wikipediaSearchApi() {
        val json = """{"batchcomplete":"","query":{"search":[{"ns":0,"title":"Mount Apo","pageid":1,"snippet":"<span class=\"searchmatch\">Mount</span> Apo is the highest mountain"}]}}"""
        val hits = SearchParsers.wikipedia(json)
        assertEquals("https://en.wikipedia.org/wiki/Mount_Apo", hits.single().url)
        assertEquals("Mount Apo is the highest mountain", hits.single().snippet)
        assertTrue(SearchParsers.wikipedia("not json").isEmpty())
        assertTrue(SearchParsers.duckDuckGo("<html>captcha</html>").isEmpty())
    }

    @Test fun readsTheRelevantPartOfAPage() {
        val page = """
            <html><head><title>Mount Apo | Facts</title><script>var x = "Mount Apo height 9999";</script><style>p{}</style></head>
            <body><nav><li>Home · About · Contact us for more information today</li></nav>
            <p>We use cookies to improve your experience. Accept all cookies to continue browsing.</p>
            <p>Mount Apo is a large solfataric, potentially active stratovolcano in the Philippines.</p>
            <p>With an elevation of 2,954 metres above sea level, Mount Apo is the highest mountain in the Philippine archipelago.</p>
            <p>The mountain is home to many endemic species of plants and animals living in its forests.</p>
            <footer><p>Copyright 2026 Example Media Group, all rights reserved worldwide.</p></footer></body></html>
        """.trimIndent()
        val text = Html.readable(page, "how high is mount apo", maxChars = 400)
        assertTrue(text.contains("2,954 metres"), text)
        assertFalse(text.contains("cookies"), text)
        assertFalse(text.contains("9999"), "scripts are not content")
        assertFalse(text.contains("Copyright"), "footers are not content")
        assertEquals("Mount Apo | Facts", Html.title(page))
        assertEquals("Tom & Jerry — “hi” é", Html.decode("Tom &amp; Jerry &mdash; &ldquo;hi&rdquo; &#233;"))
    }

    @Test fun onlyTheQuestionLeavesThePhone() {
        assertEquals("price of gold today", Research.query("price of gold today\n[About the user: name Rommel]"))
        assertEquals("best phones", Research.query("\"best phones\" [context: my sister Ana]"))
        assertTrue(Research.query("x".repeat(500)).length <= 120)
    }

    @Test fun picksReadableSitesFromDifferentDomains() {
        val hits = listOf(
            SearchHit("Video", "https://www.youtube.com/watch?v=1", ""), SearchHit("A", "https://a.com/1", ""),
            SearchHit("A2", "https://a.com/2", ""), SearchHit("B", "https://b.org/x", ""), SearchHit("C", "https://c.net/", ""), SearchHit("D", "https://d.io/", ""),
        )
        assertEquals(listOf("https://a.com/1", "https://b.org/x", "https://c.net/"), Research.pick(hits).map { it.url })
    }

    @Test fun answersCarryTheirSources() {
        val pages = listOf(WebPage("Mount Apo", "https://en.wikipedia.org/wiki/Mount_Apo", "Mount Apo is 2,954 m high."), WebPage("", "https://x.com/a", "Other."))
        val result = Research.toolResult("how high is mount apo", pages)
        assertTrue(result.contains("[1] Mount Apo — https://en.wikipedia.org/wiki/Mount_Apo"))
        assertTrue(result.contains("[2] x.com — https://x.com/a"))
        val sources = Research.sourcesFrom(result)
        assertEquals(2, sources.size)
        val answer = Research.withSources("Mount Apo is 2,954 metres high [1].", sources)
        assertTrue(answer.endsWith("[2] x.com — https://x.com/a"))
        assertEquals("See https://a.com", Research.withSources("See https://a.com", sources), "answers that already link stay as they are")
        assertTrue(Research.plainAnswer("q", pages).contains("Sources:"))
        val readable = Research.forPeople(result)
        assertTrue(readable.startsWith("Here's what I found about “how high is mount apo”:"), readable)
        assertFalse(readable.contains("Answer from these sources"))
    }

    @Test fun webResearchOfferedForQuestionsAboutTheWorld() {
        assertTrue(ToolSelector.looksFactual("who won the NBA finals"))
        assertTrue(ToolSelector.looksFactual("price of gold today"))
        assertTrue(ToolSelector.looksFactual("how tall is mount apo"))
        assertFalse(ToolSelector.looksFactual("how are you"))
        assertFalse(ToolSelector.looksFactual("what is my sister's name"))
        assertFalse(ToolSelector.looksFactual("write me a poem about rain"))
    }
}

class LearningTest {
    private fun facts(s: String) = FactPatterns.extract(s).map { it.text }

    @Test fun statedFactsAreLearned() {
        assertEquals(listOf("My sister's name is Ana."), facts("By the way my sister's name is Ana and she lives nearby"))
        assertEquals(listOf("My boss's name is Mark Reyes."), facts("Mark Reyes is my boss."))
        assertEquals(listOf("I'm allergic to peanuts."), facts("I'm allergic to peanuts, so no satay please"))
        assertEquals(listOf("I love spicy food."), facts("I love spicy food but not too much"))
        assertEquals(listOf("I work at Accenture."), facts("I work at Accenture since 2020"))
        assertEquals(listOf("I live in Cebu City."), facts("I live in Cebu City."))
        assertEquals(listOf("My birthday is March 3."), facts("my birthday is on March 3"))
        assertEquals(listOf("My favourite colour is blue."), facts("My favorite colour is blue"))
        assertEquals(listOf("I'm vegetarian."), facts("I am vegetarian"))
        assertEquals(listOf("I'm a developer."), facts("I'm a developer"))
    }

    @Test fun questionsAndMoodsAreNot() {
        assertTrue(facts("Do I like spicy food?").isEmpty())
        assertTrue(facts("What if I work at Google").isEmpty())
        assertTrue(facts("I like it").isEmpty())
        assertTrue(facts("I'm tired today").isEmpty())
        assertTrue(facts("my sister is sick").isEmpty(), "not a name")
        assertTrue(facts("set an alarm for 6").isEmpty())
    }

    @Test fun modelOutputIsReadStrictly() {
        val out = """
            Here are the facts:
            - My sister's name is Ana.
            - I prefer tea over coffee
            - The user seems tired.
            - Paris is the capital of France.
            - none
        """.trimIndent()
        assertEquals(listOf("My sister's name is Ana.", "I prefer tea over coffee."), LearningPrompt.parse(out).map { it.text })
        assertTrue(LearningPrompt.parse("- none").isEmpty())
        assertTrue(LearningPrompt.build(listOf("I love tea"), listOf("My name is Rommel.")).contains("Already known"))
    }

    @Test fun duplicatesAndSecretsStayOut() {
        val existing = listOf("My sister's name is Ana", "I like tea")
        val c = listOf(
            LearnedFact("My sister's name is Ana.", 0.8f, LearnedFact.Source.PATTERN),
            LearnedFact("My PIN is 4471", 0.6f, LearnedFact.Source.MODEL),
            LearnedFact("My card number is 4111111111111111.", 0.6f, LearnedFact.Source.MODEL),
            LearnedFact("I work at Accenture.", 0.8f, LearnedFact.Source.PATTERN),
            LearnedFact("I work at Accenture as a developer.", 0.6f, LearnedFact.Source.MODEL),
        )
        assertEquals(listOf("I work at Accenture."), LearnedFilter.accept(c, existing).map { it.text })
    }

    @Test fun onlyHardQuestionsAreThoughtTwice() {
        assertTrue(HardQuestion.isHard("Should I take the job offer in Manila or stay in Cebu with my current salary?"))
        assertTrue(HardQuestion.isHard("Plan a three day trip to Bohol for two people on a small budget"))
        assertTrue(HardQuestion.isHard("If I save 3500 a month how long until I have 100000 for a laptop"))
        assertFalse(HardQuestion.isHard("What's the capital of Japan?"))
        assertFalse(HardQuestion.isHard("should I bring an umbrella"))
        assertFalse(HardQuestion.isHard("tell me a joke"))
        assertNull(HardQuestion.correction("OK", "The answer."))
        assertNull(HardQuestion.correction("OK.", "The answer."))
        assertNull(HardQuestion.correction("ok, it's correct", "The answer."))
        assertEquals("Actually it is 2,954 metres high.", HardQuestion.correction("Actually it is 2,954 metres high.", "It is 3,000 metres high."))
    }
}
