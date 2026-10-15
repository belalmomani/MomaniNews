package com.momaninews.app.data.repository

import com.momaninews.app.data.model.NewsItem

/**
 * مصدر الأخبار للتطبيق. في المرحلة الثانية يوجد تنفيذ تجريبي محلي فقط،
 * وفي المرحلة الثالثة يُضاف تنفيذ يتصل بواجهة API عبر HTTPS ويُرجع الأخبار المعتمدة فقط.
 */
interface NewsRepository {
    suspend fun getNews(): List<NewsItem>
    suspend fun getNewsById(id: Int): NewsItem?
}
