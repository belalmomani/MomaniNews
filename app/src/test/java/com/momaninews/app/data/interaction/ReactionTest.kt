package com.momaninews.app.data.interaction

import com.momaninews.app.data.model.Reaction
import com.momaninews.app.data.model.ReactionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionTest {
    @Test fun wireRoundTrip_andUnknown() {
        Reaction.entries.forEach { assertEquals(it, Reaction.fromWire(it.wire)) }
        assertNull(Reaction.fromWire("xyz")); assertNull(Reaction.fromWire(null))
        assertEquals(7, Reaction.entries.size)
    }

    @Test fun parseCounts_skipsUnknownAndZero() {
        val m = SupabaseInteractionParser.parseReactionCounts("""[{"reaction":"like","n":3},{"reaction":"zzz","n":9},{"reaction":"love","n":0},{"reaction":"wow","n":1}]""")
        assertEquals(mapOf(Reaction.LIKE to 3, Reaction.WOW to 1), m)
        assertEquals(emptyMap<Reaction, Int>(), SupabaseInteractionParser.parseReactionCounts("[]"))
    }

    @Test fun parseMine() {
        assertEquals(Reaction.SAD, SupabaseInteractionParser.parseMyReaction("""[{"reaction":"sad"}]"""))
        assertNull(SupabaseInteractionParser.parseMyReaction("[]"))
        assertNull(SupabaseInteractionParser.parseMyReaction("""[{"reaction":"bogus"}]"""))
    }

    @Test fun summary_totalAndTopOrder() {
        val s = ReactionSummary(mapOf(Reaction.WOW to 2, Reaction.LIKE to 5, Reaction.SAD to 2, Reaction.ANGRY to 1), null)
        assertEquals(10, s.total)
        assertEquals(listOf(Reaction.LIKE, Reaction.WOW, Reaction.SAD), s.top())   // التعادل بترتيب التعريف
        assertEquals(0, ReactionSummary.EMPTY.total)
    }

    @Test fun bulkCounts_groupsByNews_ignoresUnknownAndBad() {
        val json = """[{"news_id":5,"reaction":"like","n":3},{"news_id":5,"reaction":"love","n":1},
            {"news_id":9,"reaction":"wow","n":2},{"news_id":9,"reaction":"zzz","n":4},
            {"news_id":7,"reaction":"sad","n":0},{"reaction":"like","n":1}]"""
        val m = SupabaseInteractionParser.parseBulkReactionCounts(json)
        assertEquals(setOf(5, 9), m.keys)
        assertEquals(mapOf(Reaction.LIKE to 3, Reaction.LOVE to 1), m[5])
        assertEquals(mapOf(Reaction.WOW to 2), m[9])
        assertEquals(emptyMap<Int, Map<Reaction, Int>>(), SupabaseInteractionParser.parseBulkReactionCounts("[]"))
    }

    @Test fun reactionLine_topThreeAndTotal() {
        assertNull(com.momaninews.app.data.model.ReactionLine.of(null))
        assertNull(com.momaninews.app.data.model.ReactionLine.of(emptyMap()))
        val line = com.momaninews.app.data.model.ReactionLine.of(
            mapOf(Reaction.LIKE to 5, Reaction.SAD to 1, Reaction.LOVE to 3, Reaction.WOW to 2)
        )
        assertEquals("👍❤️😮 11", line)
    }
}
