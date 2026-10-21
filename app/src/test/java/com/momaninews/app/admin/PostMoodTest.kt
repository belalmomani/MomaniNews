package com.momaninews.app.admin

import com.momaninews.app.data.posts.MoodItem
import com.momaninews.app.data.posts.PostMood
import com.momaninews.app.data.posts.PostRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostMoodTest {
    @Test fun feelingTagAttachesArticle() {
        assertEquals("🙂 أشعر بالسعادة", PostMood.feelingTag(MoodItem("🙂", "السعادة")))
    }

    @Test fun activityTagWithAndWithoutExtra() {
        val a = MoodItem("🎉", "أحتفل بـ…")
        assertEquals("🎉 أحتفل بـ نجاح ابني", PostMood.activityTag(a, "  نجاح   ابني "))
        assertEquals("🎉 أحتفل بـ", PostMood.activityTag(a, ""))
    }

    @Test fun composeAppendsLastLine() {
        assertEquals("خبر\n\n— 🙂 أشعر بالسعادة", PostMood.compose(" خبر ", "🙂 أشعر بالسعادة"))
        assertEquals("خبر", PostMood.compose("خبر", null))
    }

    @Test fun composeKeepsWithinLimit() {
        val out = PostMood.compose("س".repeat(PostRules.MAX_TEXT), "🙂 أشعر بالسعادة")
        assertTrue(out.length <= PostRules.MAX_TEXT)
        assertTrue(out.endsWith("🙂 أشعر بالسعادة"))
    }

    @Test fun filterSearches() {
        assertEquals(1, PostMood.filter(PostMood.activities, "أقرأ").size)
        assertEquals(PostMood.feelings, PostMood.filter(PostMood.feelings, " "))
    }
}
