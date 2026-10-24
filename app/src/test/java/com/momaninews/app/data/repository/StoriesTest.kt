package com.momaninews.app.data.repository

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Regions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StoriesTest {
    private fun news(id: Int) = NewsItem(id, "خبر $id", "", "", "مصدر", "", Regions.Abeen)

    private class Fake(val items: List<NewsItem>) : NewsRepository {
        override suspend fun getNews() = items
        override suspend fun getNewsById(id: Int) = items.firstOrNull { it.id == id }
    }

    @Test fun stories_areLatestTenNewestFirst() = runBlocking {
        val repo = Fake((1..25).map(::news))
        val s = repo.getStories()
        assertEquals(STORY_COUNT, s.size)
        assertEquals((25 downTo 16).toList(), s.map { it.id })
    }

    @Test fun stories_fewerThanTenReturnsAll() = runBlocking {
        assertEquals(listOf(3, 2, 1), Fake((1..3).map(::news)).getStories().map { it.id })
    }
}
