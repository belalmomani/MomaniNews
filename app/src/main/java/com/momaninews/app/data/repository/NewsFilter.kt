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
