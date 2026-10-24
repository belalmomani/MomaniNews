package com.momaninews.app.data.repository

import com.momaninews.app.data.model.NewsItem

/**
 * مصدر الأخبار للتطبيق. في المرحلة الثانية يوجد تنفيذ تجريبي محلي فقط،
 * وفي المرحلة الثالثة يُضاف تنفيذ يتصل بواجهة API عبر HTTPS ويُرجع الأخبار المعتمدة فقط.
 */
interface NewsRepository {
    suspend fun getNews(): List<NewsItem>
    suspend fun getNewsById(id: Int): NewsItem?

    /** صفحة تبدأ من [offset]. الافتراضي: الصفحة الأولى فقط. */
    suspend fun getNews(offset: Int): List<NewsItem> = if (offset == 0) getNews() else emptyList()

    /** صفحة مرتبة. الافتراضي (التجريبي المحلي) يتجاهل الترتيب. */
    suspend fun getNews(offset: Int, sort: NewsSort): List<NewsItem> = getNews(offset)

    /** يسجّل مشاهدة (مؤشر تقريبي). الافتراضي: لا شيء. */
    suspend fun trackView(newsId: Int) {}

    /** «القصص»: أحدث [limit] أخبار معتمدة (من المدير أو من المستخدمين) لشريط الرئيسية. */
    suspend fun getStories(limit: Int = STORY_COUNT): List<NewsItem> = getNews().sortedByDescending { it.id }.take(limit)

    /** حجم الصفحة؛ إن رجع أقل منه فلا يوجد المزيد. */
    val pageSize: Int get() = Int.MAX_VALUE
}

/** عدد بطاقات القصص في الرئيسية. */
const val STORY_COUNT = 10
