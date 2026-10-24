package com.momaninews.app.update

import com.momaninews.app.data.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val base = "https://github.com/belalmomani/MomaniNews/releases/download/build-105"

    private fun release(
        tag: String = "build-105", draft: Boolean = false, pre: Boolean = false,
        apk: String = "$base/MomaniNews-0.2.5.apk", sha: String = "$base/MomaniNews-0.2.5.apk.sha256"
    ) = """{"tag_name":"$tag","name":"0.2.5","body":" ملاحظات ","draft":$draft,"prerelease":$pre,
        "assets":[{"name":"MomaniNews-0.2.5.apk","browser_download_url":"$apk","size":1000},
                  {"name":"MomaniNews-0.2.5.apk.sha256","browser_download_url":"$sha","size":90}]}"""

    @Test fun tagParsing() {
        assertEquals(105L, UpdateLogic.codeFromTag("build-105"))
        assertNull(UpdateLogic.codeFromTag("v1.0"))
        assertNull(UpdateLogic.codeFromTag("build-"))
        assertNull(UpdateLogic.codeFromTag("build-0"))
        assertNull(UpdateLogic.codeFromTag("build-x"))
    }

    @Test fun newerReleaseIsOffered() {
        val i = UpdateLogic.parseRelease(release(), 1)
        assertNotNull(i)
        assertEquals(105L, i!!.versionCode)
        assertEquals("ملاحظات", i.notes)
    }

    @Test fun sameOrOlderIsIgnored() {
        assertNull(UpdateLogic.parseRelease(release(), 105))
        assertNull(UpdateLogic.parseRelease(release(), 200))
    }

    @Test fun draftAndPrereleaseIgnored() {
        assertNull(UpdateLogic.parseRelease(release(draft = true), 1))
        assertNull(UpdateLogic.parseRelease(release(pre = true), 1))
    }

    @Test fun untrustedAssetsRejected() {
        assertNull(UpdateLogic.parseRelease(release(apk = "https://evil.example/a.apk"), 1))
        assertNull(UpdateLogic.parseRelease(release(sha = "http://github.com/belalmomani/MomaniNews/releases/download/x/a.sha256"), 1))
        assertNull(UpdateLogic.parseRelease(release(apk = "https://github.com/other/Repo/releases/download/b/a.apk"), 1))
        assertNull(UpdateLogic.parseRelease(release(tag = "weird"), 1))
    }

    @Test fun urlChecks() {
        assertTrue(UpdateLogic.isTrustedAssetUrl("$base/a.apk"))
        assertFalse(UpdateLogic.isTrustedAssetUrl("https://user@github.com/belalmomani/MomaniNews/releases/download/a/b.apk"))
        assertFalse(UpdateLogic.isTrustedAssetUrl("https://github.com.evil.com/belalmomani/MomaniNews/releases/download/a"))
        assertTrue(UpdateLogic.isAllowedRedirect("https://objects.githubusercontent.com/x"))
        assertTrue(UpdateLogic.isAllowedRedirect("https://release-assets.githubusercontent.com/x"))
        assertFalse(UpdateLogic.isAllowedRedirect("http://objects.githubusercontent.com/x"))
        assertFalse(UpdateLogic.isAllowedRedirect("https://evil.com/x"))
        assertFalse(UpdateLogic.isAllowedRedirect("https://github.com.evil.com/x"))
    }

    @Test fun sha256Parsing() {
        val h = "a".repeat(64)
        assertEquals(h, UpdateLogic.parseSha256("${h.uppercase()}  MomaniNews.apk\n"))
        assertNull(UpdateLogic.parseSha256("not a hash"))
        assertNull(UpdateLogic.parseSha256("abc123"))
    }
}
