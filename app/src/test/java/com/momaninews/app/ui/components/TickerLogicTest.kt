package com.momaninews.app.ui.components

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Regions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TickerLogicTest {
    private val now = 1_800_000_000_000L
    private val h = 60L * 60 * 1000

    private fun n(id: Int, t: String, ageH: Double = 1.0, cat: String? = null, sample: Boolean = false, pub: Long? = null) =
        NewsItem(id, t, "", "", "م", "", Regions.Abeen, isSample = sample, category = cat,
            publishedAtMillis = pub ?: (now - (ageH * h).toLong()))

    private fun ids(items: List<NewsItem>) = TickerLogic.eligible(items, now).map { it.id }

    @Test fun empty_orBlank_isEmpty() {
        assertTrue(ids(emptyList()).isEmpty())
        assertTrue(ids(listOf(n(1, "   "))).isEmpty())
    }

    @Test fun samplesAndUndatedAreExcluded() {
        assertTrue(ids(listOf(n(1, "تجريبي", sample = true), n(2, "بلا تاريخ", pub = 0L))).isEmpty())
    }

    @Test fun normalNewsExpiresAfter24h_urgentAfter48h() {
        assertEquals(listOf(1), ids(listOf(n(1, "حديث", 23.0), n(2, "قديم", 25.0))))
        assertEquals(listOf(3), ids(listOf(n(3, "وفاة", 40.0, "death"), n(4, "حادث قديم", 49.0, "incident"))))
    }

    @Test fun scheduledFarFuture_isExcluded_butSmallSlackAllowed() {
        assertTrue(ids(listOf(n(1, "مجدول", pub = now + 2 * h))).isEmpty())
        assertEquals(listOf(2), ids(listOf(n(2, "انحراف ساعة", pub = now + 60_000))))
    }

    @Test fun urgentFirst_thenNewest() {
        val items = listOf(n(1, "عادي أحدث", 1.0), n(2, "وفاة أقدم", 10.0, "death"), n(3, "عادي أقدم", 5.0), n(4, "حادث", 2.0, "incident"))
        assertEquals(listOf(4, 2, 1, 3), ids(items))
    }

    @Test fun dedupTitles_andCap_andTruncate() {
        assertEquals(listOf(1), ids(listOf(n(1, "نفس العنوان", 1.0), n(2, "نفس العنوان", 2.0))))
        val many = (1..40).map { n(it, "خبر $it", it / 10.0) }
        assertEquals(TickerLogic.MAX_ITEMS, ids(many).size)
        val t = TickerLogic.eligible(listOf(n(1, "ك".repeat(200))), now)[0].title
        assertEquals(TickerLogic.MAX_TITLE, t.length); assertTrue(t.endsWith("…"))
    }

    @Test fun unreadCount_andBadgeText() {
        val e = TickerLogic.eligible(listOf(n(1, "أ"), n(2, "ب"), n(3, "ج")), now)
        assertEquals(2, TickerLogic.unreadCount(e, setOf(2)))
        assertEquals(0, TickerLogic.unreadCount(e, setOf(1, 2, 3)))
        assertNull(TickerLogic.badgeText(0)); assertEquals("5", TickerLogic.badgeText(5)); assertEquals("9+", TickerLogic.badgeText(10))
    }
}
