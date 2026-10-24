package com.momaninews.app.data.interaction

import com.momaninews.app.data.model.CredibilityVote
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SupabaseInteractionParserTest {

    @Test
    fun counts_parseAndEmptyIsZero() {
        assertEquals(3 to 1, SupabaseInteractionParser.parseCounts("""[{"credible":3,"not_credible":1}]"""))
        assertEquals(0 to 0, SupabaseInteractionParser.parseCounts("[]"))
    }

    @Test
    fun myVote_mapsAndEmptyIsNull() {
        assertEquals(CredibilityVote.NOT_CREDIBLE, SupabaseInteractionParser.parseMyVote("""[{"vote":"not_credible"}]"""))
        assertEquals(CredibilityVote.CREDIBLE, SupabaseInteractionParser.parseMyVote("""[{"vote":"credible"}]"""))
        assertNull(SupabaseInteractionParser.parseMyVote("[]"))
        assertNull(SupabaseInteractionParser.parseMyVote("""[{"vote":"other"}]"""))
    }

    @Test
    fun voteWireRoundTrip() {
        assertEquals("credible", SupabaseInteractionParser.voteToWire(CredibilityVote.CREDIBLE))
        assertEquals("not_credible", SupabaseInteractionParser.voteToWire(CredibilityVote.NOT_CREDIBLE))
    }

    @Test
    fun comments_parseWithFallbackName() {
        val list = SupabaseInteractionParser.parseComments(
            """[{"id":5,"body":"نص","created_at":"2026-10-14T09:00:00+00:00","display_name":"علي"},
                {"id":4,"body":"آخر","created_at":null,"display_name":null}]"""
        )
        assertEquals(2, list.size)
        assertEquals("علي", list[0].authorName)
        assertEquals("مستخدم", list[1].authorName)
        assertEquals("", list[1].dateLabel)
        assertEquals(false, list[0].isSample)
    }

    @Test
    fun errorMessage_readsOrEmpty() {
        assertEquals(
            "comment_rate_limited",
            SupabaseInteractionParser.errorMessage("""{"code":"P0001","message":"comment_rate_limited"}""")
        )
        assertEquals("", SupabaseInteractionParser.errorMessage("<html>"))
    }

    @Test
    fun comments_errorObjectThrows() {
        assertThrows(JSONException::class.java) {
            SupabaseInteractionParser.parseComments("""{"message":"x"}""")
        }
    }
}
