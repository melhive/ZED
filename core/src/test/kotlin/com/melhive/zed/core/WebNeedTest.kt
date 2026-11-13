package com.melhive.zed.core

import com.melhive.zed.core.understanding.WebNeed
import com.melhive.zed.core.understanding.WebNeed.Need
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebNeedTest {
    private fun query(t: String, prev: String? = null) = (WebNeed.classify(t, prev) as? Need.Search)?.query

    @Test fun searchesWhenTheQuestionNeedsTheInternet() {
        assertEquals("the Philippine Eagle", query("tell me about the Philippine Eagle"))
        assertEquals("Elon Musk", query("tell me more about Elon Musk"))
        assertEquals("who won the NBA finals", query("who won the NBA finals"))
        assertEquals("latest news in Cebu", query("latest news in Cebu"))
        assertEquals("what's the price of gold today", query("what's the price of gold today"))
        assertEquals("who is the president of France", query("who is the president of France?"))
        assertEquals("best phones 2026", query("search the web for best phones 2026"))
        assertEquals("iPhone 18 release date", query("google iPhone 18 release date"))
        assertEquals("when does SM Seaside open", query("look it up", prev = "when does SM Seaside open"))
        assertTrue((WebNeed.classify("search the internet for cheap flights to Manila") as Need.Search).explicit)
    }

    @Test fun answersAboutSearchingItself() {
        assertEquals(Need.AskedAboutSearching, WebNeed.classify("can you search the internet?"))
        assertEquals(Need.AskedAboutSearching, WebNeed.classify("Is there another way you can search the internet?"))
    }

    @Test fun personalThingsNeverGoOnline() {
        listOf("tell me about yourself", "how are you", "what's on my calendar", "what's your name", "tell me about my reminders",
            "set an alarm for 6", "thanks", "remind me to call Ana", "what is my battery").forEach { assertNull(WebNeed.classify(it), it) }
    }

    @Test fun inventedToolNamesFindTheRealTool() {
        val repair = com.melhive.zed.core.understanding.ToolRepair(com.melhive.zed.core.tools.ToolCatalog.DEFAULT)
        assertEquals("location.get", repair.toolName("location.get_user_location"))
        assertEquals("location.get", repair.toolName("knowledge.lookup_location"))
        assertEquals("web.research", repair.toolName("internet_search"))
        assertEquals("weather.get", repair.toolName("get_weather_forecast"))
    }
}
