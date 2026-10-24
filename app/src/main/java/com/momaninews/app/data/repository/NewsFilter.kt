package com.momaninews.app.data.repository

import com.momaninews.app.data.model.NewsItem

/**
 * يصفّي الأخبار حسب المنطقة (إن حُددت) ونص البحث في العنوان والمقتطف والمحتوى.
 * البحث غير حساس لحالة الأحرف اللاتينية، ولا يعالج التشكيل أو توحيد الهمزات بعد.
 */
fun filterNews(items: List<NewsItem>, query: String, regionId: String?): List<NewsItem> {
    val q = query.trim()
    return items.filter { item ->
        val regionMatches = regionId == null || item.region.id == regionId
        val textMatches = q.isEmpty() ||
            item.title.contains(q, ignoreCase = true) ||
            item.excerpt.contains(q, ignoreCase = true) ||
            item.content.contains(q, ignoreCase = true)
        regionMatches && textMatches
    }
}

/**
 * ترتيب العرض (من الخادم). الأحدث = تاريخ النشر ثم الوقت (published_at فيه الاثنان).
 * كل ترتيب ثانوي بالأحدث. «منشورات جديدة» = منشورات المستخدمين المعتمدة فقط.
 */
enum class NewsSort(val label: String, val order: String, val filter: String? = null) {
    NEWEST("الأحدث", "published_at.desc"),
    MOST_VIEWED("الأكثر مشاهدة", "view_count.desc,published_at.desc"),
    MOST_LIKED("الأكثر إعجاباً", "credible_count.desc,published_at.desc"),
    MOST_COMMENTED("الأكثر تعليقاً", "comment_count.desc,published_at.desc"),
    USER_POSTS("منشورات جديدة", "published_at.desc", "author_id=not.is.null")
}
