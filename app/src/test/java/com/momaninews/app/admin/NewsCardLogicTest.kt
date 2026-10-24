package com.momaninews.app.admin

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.model.Regions
import com.momaninews.app.ui.components.avatarLetter
import com.momaninews.app.ui.components.shareBody
import org.junit.Assert.assertEquals
import org.junit.Test

class NewsCardLogicTest {
    private fun item(src: String = "الغد", url: String? = null) =
        NewsItem(id = 5, title = "عنوان", excerpt = "", content = "", source = src, dateLabel = "", region = Regions.Abeen, sourceUrl = url)

    @Test fun avatarUsesFirstLetterOrFallback() {
        assertEquals("ا", avatarLetter("الغد"))
        assertEquals("J", avatarLetter("  jo24"))
        assertEquals("م", avatarLetter(""))
        assertEquals("م", avatarLetter("— "))
    }

    @Test fun shareBodyPrefersSharePageThenSourceUrl() {
        assertEquals("عنوان\nhttps://x.github.io/app/news.html?id=5\n\nعبر تطبيق المومني نيوز", shareBody(item(), "https://x.github.io/app/privacy.html"))
        assertEquals("عنوان\nhttps://src/1\n\nعبر تطبيق المومني نيوز", shareBody(item(url = "https://src/1"), ""))
        assertEquals("عنوان\n\nعبر تطبيق المومني نيوز", shareBody(item(), "http://x/privacy.html"))
    }
}
