package com.momaninews.app.data.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveAdsParserTest {
    @Test
    fun parse_keepsOnlyHttpsLinksAndFillsEmpties() {
        val ads = ActiveAdsParser.parse(
            """[{"id":1,"title":"أ","body":null,"contact":"07","link_url":"https://example.com"},
                {"id":2,"title":"ب","body":"نص","contact":null,"link_url":"javascript:alert(1)"},
                {"id":3,"title":"ج","body":null,"contact":null,"link_url":null}]"""
        )
        assertEquals("https://example.com", ads[0].linkUrl)
        assertEquals("", ads[0].body)
        assertNull(ads[1].linkUrl)
        assertEquals("", ads[1].contact)
        assertNull(ads[2].linkUrl)
    }
}
