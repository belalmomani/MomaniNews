package com.momaninews.app.data.repository

import com.momaninews.app.BuildConfig
import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.remote.SupabaseNewsRepository
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
 * نقطة واحدة لاختيار التنفيذ المستخدم، بالأولوية:
 * 1) Supabase إذا ضُبط رابطه ومفتاحه العام، 2) بيانات تجريبية.
 */
object NewsRepositories {
    val default: NewsRepository = when {
        BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_KEY.isNotBlank() ->
            SupabaseNewsRepository(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
        else -> SampleNewsRepository
    }
}
