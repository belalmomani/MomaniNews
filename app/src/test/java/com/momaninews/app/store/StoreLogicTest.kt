package com.momaninews.app.store

import com.momaninews.app.data.store.StoreApp
import com.momaninews.app.data.store.StoreLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreLogicTest {
    private fun app(id: Int, cat: String = "tools", v: String? = "1.0", repo: String = "o/r") =
        StoreApp(id, repo, "T$id", "", cat, null, emptyList(), null, v, null, "approved")

    @Test fun parseRepo_variants() {
        assertEquals("belalmomani/MomaniNews", StoreLogic.parseRepo("belalmomani/MomaniNews"))
        assertEquals("belalmomani/MomaniNews", StoreLogic.parseRepo(" https://github.com/belalmomani/MomaniNews.git "))
        assertEquals("a/b", StoreLogic.parseRepo("https://github.com/a/b/releases/tag/v1"))
        assertEquals("a/b", StoreLogic.parseRepo("https://github.com/a/b/"))
    }

    @Test fun parseRepo_rejects() {
        assertNull(StoreLogic.parseRepo(""))
        assertNull(StoreLogic.parseRepo("onlyname"))
        assertNull(StoreLogic.parseRepo("http://github.com/a/b"))
        assertNull(StoreLogic.parseRepo("https://evil.example/a/b"))
        assertNull(StoreLogic.parseRepo("https://user@github.com/a/b"))
        assertNull(StoreLogic.parseRepo("a b/c"))
        assertNull(StoreLogic.parseRepo("a/b;rm"))
    }

    @Test fun trustedApk_onlyOwnReleaseDownloads() {
        val ok = "https://github.com/o/r/releases/download/v1/app.apk"
        assertTrue(StoreLogic.isTrustedApk(ok, "o/r"))
        assertTrue(StoreLogic.isTrustedApk(ok, "O/R"))
        assertFalse(StoreLogic.isTrustedApk(ok, "o/other"))                                   // مستودع آخر
        assertFalse(StoreLogic.isTrustedApk("http://github.com/o/r/releases/download/v1/app.apk", "o/r"))
        assertFalse(StoreLogic.isTrustedApk("https://evil.example/o/r/releases/download/v1/app.apk", "o/r"))
        assertFalse(StoreLogic.isTrustedApk("https://github.com/o/r/releases/download/v1/app.zip", "o/r"))
        assertFalse(StoreLogic.isTrustedApk("https://github.com/o/r/archive/main.apk", "o/r"))
        assertFalse(StoreLogic.isTrustedApk("https://u@github.com/o/r/releases/download/v1/app.apk", "o/r"))
        assertFalse(StoreLogic.isTrustedApk(null, "o/r"))
    }

    @Test fun trustedImage() {
        assertTrue(StoreLogic.isTrustedImage("https://raw.githubusercontent.com/o/r/main/store/icon.png"))
        assertTrue(StoreLogic.isTrustedImage("https://github.com/o.png"))
        assertFalse(StoreLogic.isTrustedImage("http://raw.githubusercontent.com/o/r/main/store/icon.png"))
        assertFalse(StoreLogic.isTrustedImage("https://evil.example/x.png"))
        assertFalse(StoreLogic.isTrustedImage(null))
    }

    @Test fun parseLatestApk_picksTrustedApkAndVersion() {
        val json = """{"name":"الإصدار 2","tag_name":"v2","draft":false,"assets":[
            {"name":"notes.txt","browser_download_url":"https://github.com/o/r/releases/download/v2/notes.txt","size":10},
            {"name":"bad.apk","browser_download_url":"https://evil.example/bad.apk","size":5},
            {"name":"app.apk","browser_download_url":"https://github.com/o/r/releases/download/v2/app.apk","size":2048}]}"""
        val r = StoreLogic.parseLatestApk(json, "o/r")!!
        assertEquals("https://github.com/o/r/releases/download/v2/app.apk", r.first)
        assertEquals(2048L, r.second)
        assertEquals("الإصدار 2", r.third)
    }

    @Test fun parseLatestApk_noApkOrDraft() {
        assertNull(StoreLogic.parseLatestApk("""{"tag_name":"v1","assets":[]}""", "o/r"))
        assertNull(StoreLogic.parseLatestApk("""{"tag_name":"v1","draft":true,"assets":[{"name":"a.apk","browser_download_url":"https://github.com/o/r/releases/download/v1/a.apk"}]}""", "o/r"))
    }

    @Test fun pickMedia_iconAndSortedScreenshots() {
        val base = "https://raw.githubusercontent.com/o/r/main/store/"
        fun f(n: String) = """{"type":"file","name":"$n","download_url":"$base$n"}"""
        val json = "[" + listOf(f("screenshot2.png"), f("Icon.PNG"), f("screenshot1.jpg"), f("readme.md"), f("notes.png"),
            """{"type":"dir","name":"screenshot9","download_url":null}""").joinToString(",") + "]"
        val (icon, shots) = StoreLogic.pickMedia(json)
        assertEquals(base + "Icon.PNG", icon)
        assertEquals(listOf(base + "screenshot1.jpg", base + "screenshot2.png"), shots)
    }

    @Test fun pickMedia_limitsAndBadJson() {
        val base = "https://raw.githubusercontent.com/o/r/main/store/"
        val json = "[" + (1..12).joinToString(",") { """{"type":"file","name":"screenshot${"%02d".format(it)}.png","download_url":"${base}s$it.png"}""" } + "]"
        assertEquals(StoreLogic.MAX_SHOTS, StoreLogic.pickMedia(json).second.size)
        assertNull(StoreLogic.pickMedia("not json").first)
        assertTrue(StoreLogic.pickMedia("{}").second.isEmpty())
    }

    @Test fun parseApps_dropsUntrustedImages() {
        val json = """[{"id":1,"repo":"o/r","title":"تطبيق","description":null,"category":"games",
            "icon_url":"https://evil.example/i.png","screenshots":["https://raw.githubusercontent.com/o/r/main/s.png","http://x/y.png"],
            "apk_url":null,"version_name":"1.2","size_bytes":3145728,"status":"approved"}]"""
        val a = StoreLogic.parseApps(json).single()
        assertNull(a.iconUrl)
        assertEquals(1, a.screenshots.size)
        assertEquals("", a.description)
        assertEquals(3145728L, a.sizeBytes)
    }

    @Test fun formatSizeAndLabels() {
        assertEquals("", StoreLogic.formatSize(null))
        assertEquals("500 كيلوبايت", StoreLogic.formatSize(512_000))
        assertEquals("3.0 ميغابايت", StoreLogic.formatSize(3L * 1024 * 1024))
        assertEquals("ألعاب", StoreLogic.categoryLabel("games"))
        assertEquals("أخرى", StoreLogic.categoryLabel("zzz"))
    }

    @Test fun filterAndFileName() {
        val l = listOf(app(1, "games"), app(2, "tools"), app(3, "games"))
        assertEquals(listOf(1, 3), StoreLogic.filter(l, "games").map { it.id })
        assertEquals(3, StoreLogic.filter(l, null).size)
        assertEquals("MomaniNews-v1.2.apk", StoreLogic.fileName(app(1, v = "v1.2", repo = "o/MomaniNews")))
        assertEquals("my_app.apk", StoreLogic.fileName(app(1, v = null, repo = "o/my app")))
        assertFalse(StoreLogic.fileName(app(1, v = "../x", repo = "o/r")).contains("/"))
    }

    @Test fun readmeSummary_firstTextParagraph() {
        val md = "# عنوان\n\n![شارة](https://x/y.png)\n[![b](https://img/b.svg)](https://x)\n\nتطبيق **أخبار** محلي، انظر [الموقع](https://a.b) للتفاصيل.\nسطر ثانٍ.\n\n## ثانياً\nنص آخر"
        assertEquals("تطبيق أخبار محلي، انظر الموقع للتفاصيل. سطر ثانٍ.", StoreLogic.readmeSummary(md))
        assertEquals("", StoreLogic.readmeSummary("# فقط عنوان\n\n![img](a.png)"))
        assertEquals(10, StoreLogic.readmeSummary("x".repeat(50), 10).length)
    }

    @Test fun readmeImages_resolvesAndFilters() {
        val md = """
            ![a](docs/shot1.png) ![b](./shots/two.JPG?raw=1) ![c](https://raw.githubusercontent.com/o/r/main/three.webp)
            ![badge](https://img.shields.io/badge/x-y-green.svg) ![svg](docs/logo.svg) ![evil](https://evil.example/e.png)
            ![dots](../secret.png) ![http](http://raw.githubusercontent.com/o/r/main/h.png) ![dup](docs/shot1.png)
        """.trimIndent()
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/o/r/HEAD/docs/shot1.png",
                "https://raw.githubusercontent.com/o/r/HEAD/shots/two.JPG?raw=1",
                "https://raw.githubusercontent.com/o/r/main/three.webp"
            ),
            StoreLogic.readmeImages(md, "o/r")
        )
    }
}
