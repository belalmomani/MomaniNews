package com.momaninews.app.admin

import com.momaninews.app.data.notifications.PushLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class PushLogicTest {
    @Test fun parse_readsItemsAndSkipsBlankTitles() {
        val list = PushLogic.parse("""[{"id":5,"title":"عنوان","body":null},{"id":6,"title":"  ","body":"x"},{"id":7,"title":"ثانٍ","body":"نص"}]""")
        assertEquals(listOf(5L, 7L), list.map { it.id })
        assertEquals("", list[0].body)
        assertEquals("نص", list[1].body)
    }

    @Test fun maxId_ofRowsAndEmpty() {
        assertEquals(9L, PushLogic.maxId("""[{"id":3},{"id":9},{"id":4}]"""))
        assertEquals(0L, PushLogic.maxId("[]"))
    }
}
