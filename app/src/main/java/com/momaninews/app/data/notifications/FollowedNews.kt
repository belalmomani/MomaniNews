package com.momaninews.app.data.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.momaninews.app.BuildConfig
import com.momaninews.app.data.remote.SupabaseRest
import com.momaninews.app.data.settings.FollowLogic
import com.momaninews.app.data.settings.FollowStore
import java.util.concurrent.TimeUnit
import org.json.JSONArray

data class FollowedRow(val id: Long, val title: String, val source: String)

/** منطق نقي قابل للاختبار: أي أخبار جديدة تستحق إشعاراً لمتابِع هذه المصادر. */
object FollowedNewsLogic {
    const val MAX_NOTIFICATIONS = 3

    fun parse(json: String): List<FollowedRow> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            FollowedRow(
                id = r.getLong("id"),
                title = if (r.isNull("title")) "" else r.optString("title", ""),
                source = if (r.isNull("source_name")) "" else r.optString("source_name", "")
            )
        }.filter { it.title.isNotBlank() }
    }

    /** أكبر معرّف في الاستجابة (0 إن كانت فارغة). */
    fun maxId(rows: List<FollowedRow>): Long = rows.maxOfOrNull { it.id } ?: 0L

    /** أخبار أحدث من [lastId] من مصادر متابَعة، الأقدم أولاً، بحد [MAX_NOTIFICATIONS] (الأحدث منها إن زادت). */
    fun pick(rows: List<FollowedRow>, lastId: Long, followed: Set<String>): List<FollowedRow> {
        if (followed.isEmpty()) return emptyList()
        return rows.filter { it.id > lastId && FollowLogic.key(it.source) in followed }
            .sortedBy { it.id }.takeLast(MAX_NOTIFICATIONS)
    }
}

object FollowedNewsPrefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("follow_notif", Context.MODE_PRIVATE)
    /** آخر خبر تم فحصه. -1 = لم تتم التهيئة (أول مرة لا نُغرق المستخدم بأخبار قديمة). */
    fun lastId(c: Context): Long = p(c).getLong("last_news_id", -1L)
    fun setLastId(c: Context, v: Long) = p(c).edit().putLong("last_news_id", v).apply()
}

object FollowedNewsScheduler {
    fun schedule(context: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "follow-poll", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FollowedNewsWorker>(30, TimeUnit.MINUTES).setConstraints(net).build()
        )
    }
}

/**
 * إشعار محلي عند ظهور خبر معتمد جديد من مصدر يتابعه المستخدم. يعمل بلا خادم إضافي: يفحص أحدث الأخبار العامة كل ~30 دقيقة
 * (حد أندرويد الأدنى 15، وقد يتأخر في وضع توفير الطاقة). ليس إشعاراً فورياً.
 */
class FollowedNewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!PushPrefs.isEnabled(ctx)) return Result.success()
        return try {
            val rest = SupabaseRest(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
            val r = rest.call("GET", "news?select=id,title,source_name&order=id.desc&limit=40")
            if (!r.ok) return Result.retry()
            val rows = FollowedNewsLogic.parse(r.body)
            val newest = FollowedNewsLogic.maxId(rows)
            val last = FollowedNewsPrefs.lastId(ctx)
            if (last < 0) {                       // أول تشغيل: نبدأ من الآن
                FollowedNewsPrefs.setLastId(ctx, newest)
                return Result.success()
            }
            val picks = FollowedNewsLogic.pick(rows, last, FollowStore.followed.value)
            if (picks.isNotEmpty() && !PushNotifier.hasPermission(ctx)) return Result.success()   // لا نتقدم حتى يُمنح الإذن
            picks.forEach { PushNotifier.show(ctx, PushItem(1_000_000L + it.id, "جديد من " + it.source, it.title)) }
            if (newest > last) FollowedNewsPrefs.setLastId(ctx, newest)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
