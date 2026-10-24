package com.momaninews.app.data.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdsLogicTest {
    private fun d(title: String = "إعلان", body: String = "", contact: String = "", link: String = "") =
        AdDraft(title, body, contact, link, null)

    @Test
    fun clean_requiresTitleAndTrims() {
        assertNull(AdRules.clean(d(title = "   ")))
        assertEquals("إعلان", AdRules.clean(d(title = "  إعلان "))?.title)
        assertNull(AdRules.clean(d(title = "ا".repeat(AdRules.MAX_TITLE + 1))))
        assertNotNull(AdRules.clean(d(title = "ا".repeat(AdRules.MAX_TITLE))))
    }

    @Test
    fun clean_limitsBodyAndContact() {
        assertNull(AdRules.clean(d(body = "x".repeat(AdRules.MAX_BODY + 1))))
        assertNull(AdRules.clean(d(contact = "x".repeat(AdRules.MAX_CONTACT + 1))))
        assertNotNull(AdRules.clean(d(body = "x".repeat(AdRules.MAX_BODY))))
    }

    @Test
    fun clean_linkMustBeHttpsWithoutSpaces() {
        assertNotNull(AdRules.clean(d(link = "")))
        assertNotNull(AdRules.clean(d(link = " https://example.com/a?b=1 ")))
        assertNull(AdRules.clean(d(link = "http://example.com")))
        assertNull(AdRules.clean(d(link = "javascript:alert(1)")))
        assertNull(AdRules.clean(d(link = "https://exa mple.com")))
    }

    @Test
    fun parseMine_mapsStatusLabels() {
        val list = AdsParser.parseMine(
            """[{"title":"أ","status":"approved","admin_note":null,"created_at":null},
                {"title":"ب","status":"rejected","admin_note":"مخالف","created_at":null},
                {"title":"ج","status":"pending","admin_note":null,"created_at":null}]"""
        )
        assertEquals(listOf("معتمد", "مرفوض", "قيد المراجعة"), list.map { it.statusLabel })
        assertEquals("مخالف", list[1].note)
    }
}
