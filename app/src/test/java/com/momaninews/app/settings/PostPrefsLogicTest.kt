package com.momaninews.app.settings

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Regions
import com.momaninews.app.data.settings.PostPrefsLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class PostPrefsLogicTest {
    private fun n(id: Int) = NewsItem(id, "خبر $id", "", "", "م", "", Regions.Abeen)

    @Test fun toggleSaved_newestFirstAndRemoves() {
        var s = PostPrefsLogic.toggleSaved(emptyList(), 1)
        s = PostPrefsLogic.toggleSaved(s, 2)
        assertEquals(listOf(2, 1), s)
        assertEquals(listOf(2), PostPrefsLogic.toggleSaved(s, 1))
    }

    @Test fun toggleSaved_capDropsOldest() {
        val full = (1..PostPrefsLogic.MAX_SAVED).toList().reversed()
        val next = PostPrefsLogic.toggleSaved(full, 999)
        assertEquals(PostPrefsLogic.MAX_SAVED, next.size)
        assertEquals(999, next.first())
        assertEquals(false, 1 in next)
    }

    @Test fun hide_isIdempotentAndCapped() {
        assertEquals(listOf(5), PostPrefsLogic.hide(PostPrefsLogic.hide(emptyList(), 5), 5))
        val full = (1..PostPrefsLogic.MAX_HIDDEN).toList()
        val next = PostPrefsLogic.hide(full, 9999)
        assertEquals(PostPrefsLogic.MAX_HIDDEN, next.size)
        assertEquals(9999, next.last()); assertEquals(false, 1 in next)
    }

    @Test fun withoutHidden_filters() {
        assertEquals(listOf(1, 3), PostPrefsLogic.withoutHidden(listOf(n(1), n(2), n(3)), setOf(2)).map { it.id })
    }

    @Test fun parseIds_ignoresGarbage() {
        assertEquals(listOf(3, 1, 7), PostPrefsLogic.parseIds("3, 1,x,,-4,0,7,3"))
        assertEquals(emptyList<Int>(), PostPrefsLogic.parseIds(null))
        assertEquals("3,1", PostPrefsLogic.encodeIds(listOf(3, 1)))
    }
}
