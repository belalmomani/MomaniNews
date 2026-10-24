package com.momaninews.app.settings

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Regions
import com.momaninews.app.data.settings.FollowLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowLogicTest {
    private fun n(id: Int, source: String) = NewsItem(id, "خبر $id", "", "", source, "", Regions.Abeen)

    @Test fun key_normalizesCaseAndSpaces() {
        assertEquals("petra news", FollowLogic.key("  Petra   NEWS "))
        assertEquals("", FollowLogic.key("   "))
    }

    @Test fun prioritize_followedFirst_keepsOrderInsideGroups() {
        val items = listOf(n(10, "عمون"), n(9, "Saraya"), n(8, "عمون"), n(7, "petra"), n(6, "saraya"))
        val out = FollowLogic.prioritize(items, setOf("saraya", "petra"))
        assertEquals(listOf(9, 7, 6, 10, 8), out.map { it.id })
    }

    @Test fun prioritize_noFollows_unchanged() {
        val items = listOf(n(2, "أ"), n(1, "ب"))
        assertEquals(items, FollowLogic.prioritize(items, emptySet()))
    }

    @Test fun toggle_addsRemovesAndIgnoresBlank() {
        var s = FollowLogic.toggle(emptySet(), "Saraya")
        assertEquals(setOf("saraya"), s)
        s = FollowLogic.toggle(s, " SARAYA ")
        assertTrue(s.isEmpty())
        assertEquals(emptySet<String>(), FollowLogic.toggle(emptySet(), "  "))
    }

    @Test fun toggle_capsAtMax() {
        val full = (1..FollowLogic.MAX_FOLLOWED).map { "s$it" }.toSet()
        assertEquals(full, FollowLogic.toggle(full, "extra"))
        assertEquals(full - "s1", FollowLogic.toggle(full, "s1"))
    }

    @Test fun onlyFollowed_filtersKeepingOrder() {
        val items = listOf(n(5, "عمون"), n(4, "Saraya"), n(3, "عمون"), n(2, "petra"))
        assertEquals(listOf(4, 2), FollowLogic.onlyFollowed(items, setOf("saraya", "petra")).map { it.id })
        assertTrue(FollowLogic.onlyFollowed(items, emptySet()).isEmpty())
    }
}
