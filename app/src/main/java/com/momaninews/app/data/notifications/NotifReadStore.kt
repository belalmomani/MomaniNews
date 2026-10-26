package com.momaninews.app.data.notifications

import android.content.Context
import com.momaninews.app.data.settings.PostPrefsLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * حالة قراءة الإشعارات (نقية وقابلة للاختبار). المستخدم هو من يقرّر: يُعلَّم الإشعار مقروءاً عند فتحه أو بالزر،
 * ويمكن إعادته «غير مقروء». ما لم يُحدَّد محلياً:
 *  - الشخصي يتبع read_at من الخادم؛
 *  - العام (للجميع) يُعدّ غير مقروء مدة [BROADCAST_FRESH_MS] من إنشائه ثم يُعدّ مقروءاً (حتى لا يظهر الأرشيف كله «جديداً»).
 */
object NotifReadLogic {
    const val MAX = 300
    const val BROADCAST_FRESH_MS = 7L * 24 * 60 * 60 * 1000

    fun isUnread(n: AppNotification, read: Set<Int>, unread: Set<Int>, nowMillis: Long): Boolean = when {
        n.id in unread -> true
        n.id in read -> false
        n.isBroadcast -> n.createdAtMillis > 0 && nowMillis - n.createdAtMillis in 0..BROADCAST_FRESH_MS
        else -> n.isUnread
    }

    private fun push(list: List<Int>, id: Int): List<Int> = (listOf(id) + list.filter { it != id }).take(MAX)

    /** يُرجع (read, unread) بعد التعليم مقروءاً. */
    fun markRead(read: List<Int>, unread: List<Int>, id: Int): Pair<List<Int>, List<Int>> = push(read, id) to unread.filter { it != id }

    fun markUnread(read: List<Int>, unread: List<Int>, id: Int): Pair<List<Int>, List<Int>> = read.filter { it != id } to push(unread, id)

    fun markAllRead(read: List<Int>, unread: List<Int>, ids: List<Int>): Pair<List<Int>, List<Int>> {
        var r = read
        ids.forEach { r = push(r, it) }
        return r to unread.filter { it !in ids.toSet() }
    }
}

/** تخزين محلي لحالة القراءة. */
object NotifReadStore {
    private const val FILE = "notif_read"
    private val readState = MutableStateFlow<List<Int>>(emptyList())
    private val unreadState = MutableStateFlow<List<Int>>(emptyList())
    val read: StateFlow<List<Int>> = readState.asStateFlow()
    val unread: StateFlow<List<Int>> = unreadState.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        readState.value = PostPrefsLogic.parseIds(p.getString("read", null)).take(NotifReadLogic.MAX)
        unreadState.value = PostPrefsLogic.parseIds(p.getString("unread", null)).take(NotifReadLogic.MAX)
    }

    private fun apply(next: Pair<List<Int>, List<Int>>) {
        readState.value = next.first
        unreadState.value = next.second
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()
            ?.putString("read", PostPrefsLogic.encodeIds(next.first))
            ?.putString("unread", PostPrefsLogic.encodeIds(next.second))?.apply()
    }

    fun markRead(id: Int) = apply(NotifReadLogic.markRead(readState.value, unreadState.value, id))
    fun markUnread(id: Int) = apply(NotifReadLogic.markUnread(readState.value, unreadState.value, id))
    fun markAllRead(ids: List<Int>) = apply(NotifReadLogic.markAllRead(readState.value, unreadState.value, ids))
}
