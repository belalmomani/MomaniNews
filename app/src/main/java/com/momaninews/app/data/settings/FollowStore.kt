package com.momaninews.app.data.settings

import android.content.Context
import com.momaninews.app.data.model.NewsItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** منطق المتابعة (نقي، قابل للاختبار): توحيد اسم المصدر، وتقديم أخبار المصادر المتابَعة مع حفظ الترتيب. */
object FollowLogic {
    const val MAX_FOLLOWED = 50

    /** مفتاح المصدر: بلا فراغات زائدة ولا فرق في حالة الأحرف. فارغ = غير قابل للمتابعة. */
    fun key(source: String): String = source.trim().replace(Regex("\\s+"), " ").lowercase()

    /** المتابَعون أولاً (بترتيبهم الأصلي)، ثم الباقي بترتيبه. */
    fun prioritize(items: List<NewsItem>, followed: Set<String>): List<NewsItem> {
        if (followed.isEmpty()) return items
        val (mine, rest) = items.partition { key(it.source) in followed }
        return mine + rest
    }

    fun toggle(followed: Set<String>, source: String): Set<String> {
        val k = key(source)
        if (k.isEmpty()) return followed
        return if (k in followed) followed - k else if (followed.size >= MAX_FOLLOWED) followed else followed + k
    }
}

/** المصادر التي يتابعها المستخدم، محفوظة على الجهاز فقط (لا حساب ولا خادم). */
object FollowStore {
    private const val FILE = "follows"
    private const val KEY = "sources"
    private val state = MutableStateFlow<Set<String>>(emptySet())
    val followed: StateFlow<Set<String>> = state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        state.value = app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY, null)
            ?.map { FollowLogic.key(it) }?.filter { it.isNotEmpty() }?.take(FollowLogic.MAX_FOLLOWED)?.toSet() ?: emptySet()
    }

    fun toggle(source: String) {
        val next = FollowLogic.toggle(state.value, source)
        if (next == state.value) return
        state.value = next
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putStringSet(KEY, HashSet(next))?.apply()
    }
}
