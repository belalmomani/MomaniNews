package com.momaninews.app.data.remote

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsJsonParserTest {

    private val listJson = """
        {
          "items": [
            {
              "id": 1,
              "title": "عنوان أول",
              "excerpt": "مقتطف",
              "image_url": null,
              "source": "مصدر",
              "published_at": "2026-10-14T12:00:00+03:00",
              "region": { "id": "abeen", "name": "عبين" }
            },
            {
              "id": 2,
              "title": "عنوان ثان",
              "excerpt": null,
              "source": null,
              "published_at": "تاريخ-غير-صالح-للاختبار",
              "region": { "id": "sakhra", "name": "صخرة" }
            }
          ],
          "page": 1,
          "per_page": 20,
          "has_more": false
        }
    """.trimIndent()

    @Test
    fun parseList_mapsFields() {
        val items = NewsJsonParser.parseList(listJson)
        assertEquals(2, items.size)
        assertEquals(1, items[0].id)
        assertEquals("عنوان أول", items[0].title)
        assertEquals("abeen", items[0].region.id)
        assertEquals("عبين", items[0].region.nameAr)
        assertFalse(items[0].isSample)
        assertTrue(items[0].dateLabel.isNotBlank())
    }

    @Test
    fun parseList_treatsJsonNullAsEmptyText() {
        val second = NewsJsonParser.parseList(listJson)[1]
        assertEquals("", second.excerpt)
        assertEquals("", second.source)
        assertEquals("", second.content)
    }

    @Test
    fun parseList_invalidDateFallsBackToRawPrefix() {
        val second = NewsJsonParser.parseList(listJson)[1]
        assertEquals("تاريخ-غير-", second.dateLabel)
    }

    @Test
    fun parseDetail_readsContent() {
        val json = """
            {
              "id": 7,
              "title": "عنوان",
              "excerpt": "مقتطف",
              "content": "فقرة أولى\n\nفقرة ثانية",
              "source": "مصدر",
              "published_at": "2026-10-14T12:00:00+03:00",
              "region": { "id": "abelin", "name": "عبلين" }
            }
        """.trimIndent()
        val item = NewsJsonParser.parseDetail(json)
        assertEquals(7, item.id)
        assertEquals("فقرة أولى\n\nفقرة ثانية", item.content)
    }

    @Test
    fun parse_nonJsonResponse_throws() {
        // مثل صفحة HTML تعيدها الاستضافة بدل JSON.
        assertThrows(JSONException::class.java) {
            NewsJsonParser.parseList("<html><body>challenge</body></html>")
        }
    }
}
