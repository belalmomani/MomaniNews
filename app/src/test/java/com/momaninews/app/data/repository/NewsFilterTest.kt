package com.momaninews.app.data.repository

import com.momaninews.app.data.model.Regions
import com.momaninews.app.data.sample.SampleNews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsFilterTest {

    private val items = SampleNews.items

    @Test
    fun noQueryAndNoRegion_returnsAll() {
        assertEquals(items, filterNews(items, query = "", regionId = null))
        assertEquals(items, filterNews(items, query = "   ", regionId = null))
    }

    @Test
    fun regionFilter_returnsOnlyThatRegion() {
        val result = filterNews(items, query = "", regionId = Regions.Sakhra.id)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.region.id == Regions.Sakhra.id })
    }

    @Test
    fun query_matchesTitle() {
        val result = filterNews(items, query = "رياضي", regionId = null)
        assertEquals(listOf(2), result.map { it.id })
    }

    @Test
    fun queryAndRegion_areCombined() {
        // الخبر 2 في عبلين؛ طلب عبين مع نفس الكلمة لا يُرجع شيئاً.
        val result = filterNews(items, query = "رياضي", regionId = Regions.Abeen.id)
        assertTrue(result.isEmpty())
    }

    @Test
    fun query_withNoMatch_returnsEmpty() {
        assertTrue(filterNews(items, query = "zzzz-لا-يوجد", regionId = null).isEmpty())
    }

    @Test
    fun sort_serverOrders() {
        assertEquals("published_at.desc", NewsSort.NEWEST.order)
        assertEquals("view_count.desc,published_at.desc", NewsSort.MOST_VIEWED.order)
        assertEquals("credible_count.desc,published_at.desc", NewsSort.MOST_LIKED.order)
        assertEquals("comment_count.desc,published_at.desc", NewsSort.MOST_COMMENTED.order)
        assertEquals("author_id=not.is.null", NewsSort.USER_POSTS.filter)
        assertEquals(null, NewsSort.NEWEST.filter)
        assertEquals(5, NewsSort.entries.size)
    }
}
