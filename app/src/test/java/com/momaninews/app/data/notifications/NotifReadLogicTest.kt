package com.momaninews.app.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifReadLogicTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private fun n(id: Int, broadcast: Boolean, serverUnread: Boolean = false, ageDays: Long = 1) =
        AppNotification(id, "ع", "", null, "", broadcast, serverUnread, createdAtMillis = now - ageDays * day)

    @Test fun broadcast_isUnreadWhenFresh_untilLocallyRead() {
        assertTrue(NotifReadLogic.isUnread(n(1, true), emptySet(), emptySet(), now))
        assertFalse(NotifReadLogic.isUnread(n(1, true), setOf(1), emptySet(), now))
    }

    @Test fun oldBroadcast_countsAsReadByDefault() {
        assertFalse(NotifReadLogic.isUnread(n(1, true, ageDays = 8), emptySet(), emptySet(), now))
        assertTrue(NotifReadLogic.isUnread(n(1, true, ageDays = 8), emptySet(), setOf(1), now))   // المستخدم أعادها غير مقروءة
    }

    @Test fun personal_followsServerUnlessOverridden() {
        assertTrue(NotifReadLogic.isUnread(n(2, false, serverUnread = true), emptySet(), emptySet(), now))
        assertFalse(NotifReadLogic.isUnread(n(2, false, serverUnread = true), setOf(2), emptySet(), now))
        assertTrue(NotifReadLogic.isUnread(n(3, false, serverUnread = false), emptySet(), setOf(3), now))
    }

    @Test fun markReadAndUnread_moveBetweenLists() {
        val (r1, u1) = NotifReadLogic.markRead(listOf(5), listOf(7, 9), 7)
        assertEquals(listOf(7, 5), r1); assertEquals(listOf(9), u1)
        val (r2, u2) = NotifReadLogic.markUnread(r1, u1, 7)
        assertEquals(listOf(5), r2); assertEquals(listOf(7, 9), u2)
    }

    @Test fun markAll_clearsOverrides_andIsCapped() {
        val (r, u) = NotifReadLogic.markAllRead(emptyList(), listOf(1, 2, 3), listOf(1, 2))
        assertEquals(listOf(2, 1), r); assertEquals(listOf(3), u)
        val big = NotifReadLogic.markAllRead(emptyList(), emptyList(), (1..500).toList()).first
        assertEquals(NotifReadLogic.MAX, big.size)
    }
}
