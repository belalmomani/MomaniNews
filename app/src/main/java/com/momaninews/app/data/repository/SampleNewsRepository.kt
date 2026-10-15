package com.momaninews.app.data.repository

import com.momaninews.app.BuildConfig
import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.remote.ApiNewsRepository
import com.momaninews.app.data.sample.SampleNews
import kotlinx.coroutines.delay

private const val SIMULATED_DELAY_MS = 700L

/** تنفيذ تجريبي محلي مع تأخير بسيط ليظهر مؤشر التحميل. لا يفشل أبداً. */
object SampleNewsRepository : NewsRepository {

    override suspend fun getNews(): List<NewsItem> {
        delay(SIMULATED_DELAY_MS)
        return SampleNews.items
    }

    override suspend fun getNewsById(id: Int): NewsItem? {
        delay(SIMULATED_DELAY_MS)
        return SampleNews.items.firstOrNull { it.id == id }
    }
}

/**
 * نقطة واحدة لاختيار التنفيذ المستخدم.
 * إذا لم يُضبط apiBaseUrl عند البناء (أو لم يكن https) تبقى البيانات التجريبية.
 */
object NewsRepositories {
    val default: NewsRepository =
        if (BuildConfig.API_BASE_URL.startsWith("https://")) {
            ApiNewsRepository(BuildConfig.API_BASE_URL)
        } else {
            SampleNewsRepository
        }
}
