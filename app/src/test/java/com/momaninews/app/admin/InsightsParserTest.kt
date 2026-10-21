package com.momaninews.app.admin

import com.momaninews.app.data.admin.AdminItem
import com.momaninews.app.data.admin.AdminParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsParserTest {
    private val json = """{"top_news":[{"id":7,"title":"خبر","views":120,"credible":9,"comments":3}],
      "sources":[{"name":"الرأي","total":10,"approved":4,"rejected":6,"pending":0},{"name":"فارغ","total":0,"approved":0,"rejected":0,"pending":0}],
      "posts":{"total":8,"approved":6,"rejected":1,"pending":1,"last_7d":3},
      "flagged_pending":2,"blocked_words":4,"comments_hidden":5}"""

    @Test fun parsesInsights() {
        val i = AdminParser.insights(json)
        assertEquals("👁 120 • 👍 9 • 💬 3", i.topNews[0].meta)
        assertTrue(i.sources[0].meta.contains("قبول 40%"))
        assertTrue(i.sources[0].flag)
        assertTrue(!i.sources[1].meta.contains("قبول"))
        assertEquals("75%", i.summary.first { it.ref == "posts_rate" }.meta)
        assertTrue(i.summary.first { it.ref == "flagged" }.flag)
    }

    @Test fun emptyJsonDoesNotCrash() {
        val i = AdminParser.insights("{}")
        assertTrue(i.topNews.isEmpty() && i.sources.isEmpty())
    }

    @Test fun flaggedItemsComeFirst() {
        val items = listOf(AdminItem("1", "أ", "م"), AdminItem("2", "ب", "م"), AdminItem("3", "ج", "م"))
        val out = AdminParser.markFlagged(items, mapOf("3" to "كلمة محظورة: س"))
        assertEquals(listOf("3", "1", "2"), out.map { it.ref })
        assertTrue(out[0].title.startsWith("⚠ "))
        assertEquals(items, AdminParser.markFlagged(items, emptyMap()))
    }

    @Test fun parsesFlags() {
        assertEquals(mapOf("5" to "ر"), AdminParser.flags("""[{"news_id":5,"reason":"ر"}]"""))
    }
}
