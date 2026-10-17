package com.momaninews.app.admin

import com.momaninews.app.data.admin.AdminParser
import com.momaninews.app.data.admin.AdminRules
import com.momaninews.app.data.auth.SupabaseAuthParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminLogicTest {
    @Test fun parseDays_acceptsOnlyOneTo365() {
        assertEquals(7, AdminRules.parseDays(" 7 "))
        assertEquals(1, AdminRules.parseDays("1"))
        assertEquals(365, AdminRules.parseDays("365"))
        assertNull(AdminRules.parseDays("0"))
        assertNull(AdminRules.parseDays("366"))
        assertNull(AdminRules.parseDays("-3"))
        assertNull(AdminRules.parseDays("abc"))
        assertNull(AdminRules.parseDays(""))
        assertNull(AdminRules.parseDays(null))
    }

    @Test fun adWindow_endsAfterDays() {
        val (s, e) = AdminRules.adWindow(Instant.parse("2026-10-01T00:00:00Z"), 7)
        assertEquals("2026-10-01T00:00:00Z", s)
        assertEquals("2026-10-08T00:00:00Z", e)
    }

    @Test fun extendedEnd_usesLaterOfNowAndCurrentEnd() {
        val now = Instant.parse("2026-10-10T00:00:00Z")
        assertEquals("2026-10-22T00:00:00Z", AdminRules.extendedEnd(now, Instant.parse("2026-10-15T00:00:00Z"), 7))
        assertEquals("2026-10-17T00:00:00Z", AdminRules.extendedEnd(now, Instant.parse("2026-10-01T00:00:00Z"), 7))
        assertEquals("2026-10-17T00:00:00Z", AdminRules.extendedEnd(now, null, 7))
    }

    @Test fun safeId_rejectsInjection() {
        assertEquals("12", AdminRules.safeId("12"))
        for (bad in listOf("", "1&status=eq.x", "1,2", "-1", "a", "1 or 1=1")) {
            try { AdminRules.safeId(bad); throw AssertionError("accepted $bad") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun safeUuid_rejectsInjection() {
        val ok = "aaaaaaaa-0000-0000-0000-000000000001"
        assertEquals(ok, AdminRules.safeUuid(ok))
        for (bad in listOf("", "x", "$ok&role=eq.admin", "aaaaaaaa-0000-0000-0000-00000000000g")) {
            try { AdminRules.safeUuid(bad); throw AssertionError("accepted $bad") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun cleanText_trimsAndCaps() {
        assertEquals("ab", AdminRules.cleanText("  abc ", 2))
        assertEquals("", AdminRules.cleanText("   ", 5))
    }

    @Test fun parseNews_handlesNullsAndRegion() {
        val list = AdminParser.news("""[{"id":5,"title":"خبر","excerpt":null,"source_name":"ص","created_at":"2026-10-16T01:00:00+00:00","regions":{"name_ar":"عبين"}},{"id":6,"title":"بلا منطقة","excerpt":"م","source_name":null,"created_at":null,"regions":null}]""")
        assertEquals(2, list.size)
        assertEquals("5", list[0].ref)
        assertTrue(list[0].meta.startsWith("عبين • ص"))
        assertEquals("", list[0].body)
        assertEquals("", list[1].meta)
        assertEquals("م", list[1].body)
    }

    @Test fun parseComments_marksHidden() {
        val list = AdminParser.comments("""[{"id":1,"body":"أ","status":"hidden","news_id":3,"created_at":null},{"id":2,"body":"ب","status":"visible","news_id":3,"created_at":null}]""")
        assertTrue(list[0].flag); assertFalse(list[1].flag)
        assertTrue(list[0].meta.startsWith("مخفي"))
        assertTrue(list[1].meta.startsWith("ظاهر"))
    }

    @Test fun parseUsers_roleAndBan() {
        val list = AdminParser.users("""[{"id":"u1","display_name":"بلال","email":"b@x","role":"admin","is_banned":false},{"id":"u2","display_name":"","email":null,"role":"user","is_banned":true}]""")
        assertEquals("بلال (مدير)", list[0].title)
        assertEquals("admin", list[0].extra)
        assertEquals("مستخدم — محظور", list[1].title)
        assertTrue(list[1].flag)
        assertEquals("", list[1].meta)
    }

    @Test fun parseVerifications_keepsDocumentPath() {
        val list = AdminParser.verifications("""[{"id":3,"user_id":"u","full_name":"علي أحمد","document_path":"u/id.jpg","created_at":null}]""")
        assertEquals("u/id.jpg", list[0].extra)
        assertEquals("علي أحمد", list[0].title)
    }

    @Test fun parseAds_endsAtKept() {
        val list = AdminParser.ads("""[{"id":4,"title":"إعلان","body":null,"contact":"07","created_at":"2026-10-01T00:00:00+00:00","ends_at":"2026-12-01T00:00:00+00:00"}]""")
        assertEquals("2026-12-01T00:00:00+00:00", list[0].extra)
        assertTrue(list[0].meta.startsWith("07 • حتى"))
    }

    @Test fun signedUrl_onlyAcceptsObjectSignPaths() {
        assertEquals(
            "https://p.supabase.co/storage/v1/object/sign/verification-docs/u/id.jpg?token=t",
            AdminParser.signedUrl("https://p.supabase.co/", """{"signedURL":"/object/sign/verification-docs/u/id.jpg?token=t"}""")
        )
        assertNull(AdminParser.signedUrl("https://p.supabase.co", """{"signedURL":"https://evil.example/x"}"""))
        assertNull(AdminParser.signedUrl("https://p.supabase.co", """{}"""))
    }

    @Test fun profile_isAdminOnlyWhenAdminAndNotBanned() {
        fun p(role: String, banned: Boolean) = SupabaseAuthParser.parseProfile(
            """[{"id":"u","email":"e","display_name":"n","verification":"none","role":"$role","is_banned":$banned}]"""
        )!!.isAdmin
        assertTrue(p("admin", false))
        assertFalse(p("admin", true))
        assertFalse(p("user", false))
        assertFalse(SupabaseAuthParser.parseProfile("""[{"id":"u","display_name":"n"}]""")!!.isAdmin)
    }

    @Test fun mediaPath_validatesParts() {
        assertEquals("202610/abcdefgh12345678.jpg", AdminRules.mediaPath("202610", "abcdefgh12345678", "jpg"))
        assertEquals("202610/abcdefgh12345678.mp4", AdminRules.mediaPath("202610", "abcdefgh12345678", "mp4"))
        for ((ym, r, e) in listOf(
            Triple("2026-10", "abcdefgh12345678", "jpg"),
            Triple("202610", "../../evil1234", "jpg"),
            Triple("202610", "abcdefgh12345678", "exe"),
            Triple("202610", "short", "jpg")
        )) {
            try { AdminRules.mediaPath(ym, r, e); throw AssertionError("accepted $ym $r $e") } catch (x: IllegalArgumentException) { }
        }
    }

    @Test fun mp4Only_andPublicUrl() {
        assertTrue(AdminRules.isMp4("video/mp4"))
        assertFalse(AdminRules.isMp4("video/quicktime"))
        assertFalse(AdminRules.isMp4(null))
        assertEquals(
            "https://p.supabase.co/storage/v1/object/public/news-media/202610/a.jpg",
            AdminRules.publicMediaUrl("https://p.supabase.co/", "202610/a.jpg")
        )
    }

    @Test fun newsParser_keepsOnlyHttpsMedia() {
        val items = com.momaninews.app.data.remote.SupabaseNewsParser.parseList(
            """[{"id":1,"title":"ت","excerpt":null,"content":"ن","source_name":null,"published_at":null,
                 "image_url":"https://x.y/a.jpg","video_url":"javascript:alert(1)","regions":{"id":"abeen","name_ar":"عبين"}},
                {"id":2,"title":"ت","excerpt":null,"content":"ن","source_name":null,"published_at":null,
                 "image_url":null,"video_url":"https://x.y/v.mp4","regions":{"id":"abeen","name_ar":"عبين"}},
                {"id":3,"title":"ت","excerpt":null,"content":"ن","source_name":null,"published_at":null,
                 "regions":{"id":"abeen","name_ar":"عبين"}}]"""
        )
        assertEquals("https://x.y/a.jpg", items[0].imageUrl)
        assertNull(items[0].videoUrl)
        assertNull(items[1].imageUrl)
        assertEquals("https://x.y/v.mp4", items[1].videoUrl)
        assertNull(items[2].imageUrl); assertNull(items[2].videoUrl)
    }
}
