package com.momaninews.app.data.notifications

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsParserTest {
    @Test
    fun parse_personalUnreadVsBroadcastVsRead() {
        val list = NotificationsParser.parse(
            """[
              {"id":3,"user_id":"u","title":"خاص","body":null,"news_id":7,"read_at":null,"created_at":"2026-10-14T09:00:00+00:00"},
              {"id":2,"user_id":null,"title":"عام","body":"نص","news_id":null,"read_at":null,"created_at":null},
              {"id":1,"user_id":"u","title":"مقروء","body":"","news_id":null,"read_at":"2026-10-14T10:00:00+00:00","created_at":null}
            ]"""
        )
        assertTrue(list[0].isUnread); assertEquals(7, list[0].newsId); assertEquals("", list[0].body)
        assertTrue(list[1].isBroadcast); assertFalse(list[1].isUnread); assertNull(list[1].newsId)
        assertFalse(list[2].isUnread)
    }

    @Test
    fun parse_errorObjectThrows() {
        assertThrows(JSONException::class.java) { NotificationsParser.parse("""{"message":"x"}""") }
    }
}
