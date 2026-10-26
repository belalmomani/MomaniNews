package com.momaninews.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindTest {
    private fun n(id: Int, img: String? = null, vid: String? = null) =
        NewsItem(id, "خبر $id", "", "", "م", "", Regions.Abeen, imageUrl = img, videoUrl = vid)

    private val items = listOf(n(1), n(2, img = "https://a/x.jpg"), n(3, vid = "https://a/v.mp4"), n(4, img = "https://a/y.jpg", vid = "https://a/w.mp4"), n(5, img = " "))

    @Test fun photos_onlyWithImage() = assertEquals(listOf(2, 4), MediaKind.filter(items, MediaKind.PHOTOS).map { it.id })
    @Test fun videos_onlyWithVideo() = assertEquals(listOf(3, 4), MediaKind.filter(items, MediaKind.VIDEOS).map { it.id })
    @Test fun nullKind_returnsAll() = assertEquals(items, MediaKind.filter(items, null))
}
