package com.momaninews.app.admin

import com.momaninews.app.data.admin.AdminParser
import com.momaninews.app.data.posts.PostRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostRulesTest {
    @Test fun cleanTextTrimsAndBounds() {
        assertEquals("خبر", PostRules.cleanText("  خبر \n"))
        assertNull(PostRules.cleanText("   \n "))
        assertNull(PostRules.cleanText("ك".repeat(PostRules.MAX_TEXT + 1)))
        assertEquals(PostRules.MAX_TEXT, PostRules.cleanText("ك".repeat(PostRules.MAX_TEXT))!!.length)
    }

    @Test fun titleIsFirstNonEmptyLine() {
        assertEquals("السطر الأول", PostRules.titleOf("\n  السطر الأول  \nالثاني"))
        assertEquals("", PostRules.titleOf("  \n "))
    }

    @Test fun longTitleIsCutAtWordBoundary() {
        val words = (1..40).joinToString(" ") { "كلمة$it" }
        val t = PostRules.titleOf(words)
        assertTrue(t.endsWith("…"))
        assertTrue(t.length <= PostRules.MAX_TITLE + 1)
        assertTrue(!t.dropLast(1).endsWith(" "))
        assertEquals(PostRules.MAX_TITLE + 1, PostRules.titleOf("ك".repeat(300)).length)
    }

    @Test fun statusLabels() {
        assertEquals("منشور", PostRules.statusLabel("approved"))
        assertEquals("لم يُنشر", PostRules.statusLabel("rejected"))
        assertEquals("بانتظار اعتماد المدير", PostRules.statusLabel("pending"))
    }

    @Test fun imagePathAndUrlStayInUserFolder() {
        assertEquals("u-1/abc.jpg", PostRules.imagePath("u-1", "abc"))
        assertEquals("https://p.supabase.co/storage/v1/object/public/user-posts/u-1/abc.jpg",
            PostRules.publicImageUrl("https://p.supabase.co/", "u-1/abc.jpg"))
    }

    @Test fun parseMine() {
        val l = PostRules.parseMine("""[{"id":1,"title":"أ","status":"approved","created_at":"2026-10-16T01:00:00+00:00"},{"id":2,"title":"ب","status":"pending","created_at":null}]""")
        assertEquals(2, l.size)
        assertEquals("منشور", l[0].statusLabel)
        assertEquals("بانتظار اعتماد المدير", l[1].statusLabel)
        assertEquals("", l[1].dateLabel)
    }

    @Test fun adminSeesUserPostContentAndFeaturedFlag() {
        val l = AdminParser.news("""[{"id":9,"title":"ت","excerpt":null,"content":"النص الكامل","image_url":"https://x/y.jpg","author_id":"u1","is_featured":true,"source_name":"علي","created_at":null,"regions":{"name_ar":"عبين"}}]""")
        assertTrue(l[0].meta.contains("منشور مستخدم"))
        assertTrue(l[0].meta.contains("مميّز"))
        assertTrue(l[0].body.contains("النص الكامل"))
        assertTrue(l[0].body.contains("الصورة: https://x/y.jpg"))
        assertTrue(l[0].flag)
    }

    @Test fun fetchedNewsNotMarkedAsUserPost() {
        val l = AdminParser.news("""[{"id":1,"title":"ت","excerpt":"م","author_id":null,"source_name":"ص","created_at":null,"regions":{"name_ar":"عبين"}}]""")
        assertTrue(!l[0].meta.contains("منشور مستخدم"))
        assertTrue(!l[0].flag)
    }
}
