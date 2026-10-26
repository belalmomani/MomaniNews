package com.momaninews.app.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** منطق نقي للأخبار المقروءة (قابل للاختبار): الأحدث في المقدمة وبحد أقصى. */
object ReadLogic {
    const val MAX_READ = 300

    fun markRead(read: List<Int>, id: Int): List<Int> =
        if (id <= 0 || (read.isNotEmpty() && read.first() == id)) read else (listOf(id) + read.filter { it != id }).take(MAX_READ)
}

/** الأخبار التي فتحها المستخدم (على الجهاز فقط). تُغذّي عدّاد «غير المقروء» على الجرس. */
object ReadStore {
    private const val FILE = "read_news"
    private const val KEY = "ids"
    private val state = MutableStateFlow<List<Int>>(emptyList())
    val read: StateFlow<List<Int>> = state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        state.value = PostPrefsLogic.parseIds(app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)).take(ReadLogic.MAX_READ)
    }

    fun markRead(id: Int) {
        val next = ReadLogic.markRead(state.value, id)
        if (next === state.value) return
        state.value = next
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString(KEY, PostPrefsLogic.encodeIds(next))?.apply()
    }
}
