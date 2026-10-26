package com.momaninews.app.ui.components

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Regions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TickerLogicTest {
    private fun n(id: Int, t: String) = NewsItem(id, t, "", "", "م", "", Regions.Abeen)

    @Test fun empty_isNull() {
        assertNull(TickerLogic.text(emptyList()))
        assertNull(TickerLogic.text(listOf(n(1, "   "))))
    }

    @Test fun newestFirst_dedup_andSeparator() {
        val t = TickerLogic.text(listOf(n(1, "قديم"), n(3, "جديد"), n(2, "وسط"), n(4, "جديد")))!!
        assertEquals("جديد   •   وسط   •   قديم", t)
    }

    @Test fun longTitleTruncated_andCappedCount() {
        val long = "ك".repeat(200)
        val t = TickerLogic.text(listOf(n(1, long)))!!
        assertEquals(TickerLogic.MAX_TITLE, t.length)
        assertTrue(t.endsWith("…"))
        val many = (1..40).map { n(it, "خبر $it") }
        assertEquals(TickerLogic.MAX_ITEMS, TickerLogic.text(many)!!.split("   •   ").size)
    }

    @Test fun whitespaceNormalized() {
        assertEquals("خبر عاجل هنا", TickerLogic.text(listOf(n(1, "  خبر   عاجل\nهنا ")))!!)
    }
}
