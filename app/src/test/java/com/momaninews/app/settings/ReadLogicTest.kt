package com.momaninews.app.settings

import com.momaninews.app.data.settings.ReadLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReadLogicTest {
    @Test fun addsNewestFirst_andMovesExisting() {
        assertEquals(listOf(3, 2, 1), ReadLogic.markRead(listOf(2, 1), 3))
        assertEquals(listOf(1, 2, 3), ReadLogic.markRead(listOf(2, 1, 3), 1))
    }

    @Test fun sameHeadOrInvalid_returnsSameList() {
        val l = listOf(5, 4)
        assertSame(l, ReadLogic.markRead(l, 5))
        assertSame(l, ReadLogic.markRead(l, 0))
    }

    @Test fun capped() {
        val full = (1..ReadLogic.MAX_READ).toList()
        val next = ReadLogic.markRead(full, 9999)
        assertEquals(ReadLogic.MAX_READ, next.size); assertEquals(9999, next.first())
    }
}
