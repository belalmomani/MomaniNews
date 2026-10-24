package com.momaninews.app.data.settings

import android.content.Context
import com.momaninews.app.data.model.NewsItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** منطق نقي لتفضيلات المنشورات (الحفظ والإخفاء): قابل للاختبار بلا أندرويد. */
object PostPrefsLogic {
    const val MAX_SAVED = 200
    const val MAX_HIDDEN = 500

    /** يضيف المعرّف في المقدمة (الأحدث أولاً) أو يزيله إن كان موجوداً. عند تجاوز الحد يُحذف الأقدم. */
    fun toggleSaved(saved: List<Int>, id: Int): List<Int> =
        if (id in saved) saved - id else (listOf(id) + saved).take(MAX_SAVED)

    /** الإخفاء لا يتراجع تلقائياً؛ عند تجاوز الحد يُحذف أقدم مخفي (ترتيب الإدراج). */
    fun hide(hidden: List<Int>, id: Int): List<Int> =
        if (id in hidden) hidden else (hidden + id).takeLast(MAX_HIDDEN)

    fun withoutHidden(items: List<NewsItem>, hidden: Set<Int>): List<NewsItem> =
        if (hidden.isEmpty()) items else items.filter { it.id !in hidden }

    /** معرّفات مفصولة بفاصلة، وتتجاهل أي قيمة تالفة. */
    fun parseIds(raw: String?): List<Int> =
        raw.orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct()

    fun encodeIds(ids: List<Int>): String = ids.joinToString(",")
}

/** المنشورات المحفوظة والمخفية، على الجهاز فقط. */
object PostPrefs {
    private const val FILE = "post_prefs"
    private const val KEY_SAVED = "saved"
    private const val KEY_HIDDEN = "hidden"

    private val savedState = MutableStateFlow<List<Int>>(emptyList())
    private val hiddenState = MutableStateFlow<List<Int>>(emptyList())
    val saved: StateFlow<List<Int>> = savedState.asStateFlow()
    val hidden: StateFlow<List<Int>> = hiddenState.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        savedState.value = PostPrefsLogic.parseIds(p.getString(KEY_SAVED, null)).take(PostPrefsLogic.MAX_SAVED)
        hiddenState.value = PostPrefsLogic.parseIds(p.getString(KEY_HIDDEN, null)).takeLast(PostPrefsLogic.MAX_HIDDEN)
    }

    fun toggleSaved(id: Int) {
        savedState.value = PostPrefsLogic.toggleSaved(savedState.value, id)
        persist(KEY_SAVED, savedState.value)
    }

    fun hide(id: Int) {
        hiddenState.value = PostPrefsLogic.hide(hiddenState.value, id)
        persist(KEY_HIDDEN, hiddenState.value)
    }

    private fun persist(key: String, ids: List<Int>) {
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString(key, PostPrefsLogic.encodeIds(ids))?.apply()
    }
}
