package com.momaninews.app.admin

import com.momaninews.app.data.notifications.FcmLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FcmLogicTest {
    @Test fun parsesValidMessage() {
        val i = FcmLogic.parse(mapOf("id" to "7", "title" to " عنوان ", "body" to "نص"))!!
        assertEquals(7L, i.id); assertEquals("عنوان", i.title); assertEquals("نص", i.body)
    }
    @Test fun rejectsMissingTitleOrBadId() {
        assertNull(FcmLogic.parse(mapOf("id" to "7", "title" to "  ")))
        assertNull(FcmLogic.parse(mapOf("id" to "x", "title" to "t")))
        assertNull(FcmLogic.parse(mapOf("title" to "t")))
    }
    @Test fun truncatesLongText() {
        val i = FcmLogic.parse(mapOf("id" to "1", "title" to "a".repeat(300), "body" to "b".repeat(900)))!!
        assertEquals(120, i.title.length); assertEquals(500, i.body.length)
    }
    @Test fun topicName() = assertEquals("news", FcmLogic.TOPIC)
}
