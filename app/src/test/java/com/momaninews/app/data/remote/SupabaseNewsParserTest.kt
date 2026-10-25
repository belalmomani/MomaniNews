package com.momaninews.app.data.remote

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SupabaseNewsParserTest {

    private val json = """
        [
          {"id": 7, "title": "عنوان", "excerpt": "مقتطف", "content": "نص كامل",
           "source_name": "مصدر", "published_at": "2026-10-14T09:00:00+00:00",
           "regions": {"id": "abeen", "name_ar": "عبين"}},
          {"id": 8, "title": "بلا مقتطف", "excerpt": null, "content": "محتوى الخبر هنا",
           "source_name": null, "published_at": "غير-صالح",
           "regions": {"id": "sakhra", "name_ar": "صخرة"}}
        ]
    """.trimIndent()

    @Test
    fun parseList_mapsFields() {
        val items = SupabaseNewsParser.parseList(json)
        assertEquals(2, items.size)
        assertEquals(7, items[0].id)
        assertEquals("عبين", items[0].region.nameAr)
        assertEquals("مصدر", items[0].source)
        assertFalse(items[0].isSample)
    }

    @Test
    fun parseList_nullFieldsBecomeEmptyOrFallback() {
        val second = SupabaseNewsParser.parseList(json)[1]
        assertEquals("", second.source)
        assertEquals("محتوى الخبر هنا", second.excerpt) // المقتطف الفارغ يُستبدل ببداية المحتوى
        assertEquals("غير-صالح", second.dateLabel)       // أول 10 أحرف عند فشل التاريخ
    }

    @Test
    fun parseFirstOrNull_emptyArrayIsNull() {
        assertNull(SupabaseNewsParser.parseFirstOrNull("[]"))
    }

    @Test
    fun parseFirstOrNull_returnsFirst() {
        assertEquals(7, SupabaseNewsParser.parseFirstOrNull(json)?.id)
    }

    @Test
    fun parse_nonJsonThrows() {
        assertThrows(JSONException::class.java) { SupabaseNewsParser.parseList("<html>") }
    }

    @Test
    fun parse_errorObjectThrows() {
        // PostgREST يُرجع كائناً (لا مصفوفة) عند الخطأ
        assertThrows(JSONException::class.java) {
            SupabaseNewsParser.parseList("""{"code":"42501","message":"denied"}""")
        }
    }

    @Test
    fun category_knownKeptUnknownOrMissingNull() {
        val j = """
            [{"id":1,"title":"أ","content":"ن","regions":{"id":"abeen","name_ar":"عبين"},"category":"death"},
             {"id":2,"title":"ب","content":"ن","regions":{"id":"abeen","name_ar":"عبين"},"category":"zzz"},
             {"id":3,"title":"ج","content":"ن","regions":{"id":"abeen","name_ar":"عبين"},"category":null},
             {"id":4,"title":"د","content":"ن","regions":{"id":"abeen","name_ar":"عبين"}}]
        """.trimIndent()
        val c = SupabaseNewsParser.parseList(j).map { it.category }
        assertEquals(listOf("death", null, null, null), c)
    }

    @Test
    fun categoryFilter_keepsOnlyMatchingAndAllWhenNull() {
        val items = SupabaseNewsParser.parseList("""
            [{"id":1,"title":"أ","content":"ن","regions":{"id":"abeen","name_ar":"عبين"},"category":"death"},
             {"id":2,"title":"ب","content":"ن","regions":{"id":"abeen","name_ar":"عبين"},"category":"sports"}]
        """.trimIndent())
        assertEquals(listOf(1), com.momaninews.app.data.model.NewsCategories.filter(items, "death").map { it.id })
        assertEquals(2, com.momaninews.app.data.model.NewsCategories.filter(items, null).size)
        assertEquals(7, com.momaninews.app.data.model.NewsCategories.all.size)
    }
}
