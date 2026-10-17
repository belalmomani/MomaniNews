package com.momaninews.app.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.momaninews.app.BuildConfig
import com.momaninews.app.MainActivity
import com.momaninews.app.R
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.SupabaseAuthRepository
import com.momaninews.app.data.remote.SupabaseRest
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/** إشعار جديد يُعرض على الهاتف. */
data class PushItem(val id: Long, val title: String, val body: String)

/** منطق نقي قابل للاختبار: تحليل الاستجابة وتحديد آخر معرّف. */
object PushLogic {
    fun parse(json: String): List<PushItem> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            PushItem(
                id = r.getLong("id"),
                title = if (r.isNull("title")) "" else r.optString("title", ""),
                body = if (r.isNull("body")) "" else r.optString("body", "")
            )
        }.filter { it.title.isNotBlank() }
    }

    /** أكبر معرّف في استجابة تحوي حقل id فقط (للتهيئة الأولى بلا إشعارات قديمة). */
    fun maxId(json: String): Long? {
        val a = JSONArray(json)
        if (a.length() == 0) return 0L
        return (0 until a.length()).maxOf { a.getJSONObject(it).getLong("id") }
    }
}

object PushPrefs {
    private const val FILE = "push_prefs"
    private fun p(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun isEnabled(c: Context): Boolean = p(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()
    /** آخر إشعار عُرض/تُجوهل عند التهيئة. -1 = لم تتم التهيئة. */
    fun lastId(c: Context): Long = p(c).getLong("last_id", -1L)
    fun setLastId(c: Context, v: Long) = p(c).edit().putLong("last_id", v).apply()
}

object PushNotifier {
    const val CHANNEL_ID = "news"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "الأخبار والإشعارات", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, item: PushItem) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(item.title)
            .setContentText(item.body.ifBlank { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body.ifBlank { item.title }))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(item.id.toInt(), n)
        } catch (e: SecurityException) {
            // لا صلاحية إشعارات: نتجاهل.
        }
    }

    /**
     * يجدول الفحص الدوري (كل 15 دقيقة كحد أدنى في أندرويد) + فحصاً فورياً عند فتح التطبيق.
     * ليس فورياً كخدمة Push حقيقية (FCM)؛ قد يتأخر الإشعار حتى ~15 دقيقة أو أكثر في وضع توفير الطاقة.
     */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            "notif-poll", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES).setConstraints(net).build()
        )
        wm.enqueueUniqueWork(
            "notif-poll-now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NotificationWorker>().setConstraints(net).build()
        )
    }
}

class NotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!PushPrefs.isEnabled(ctx)) return Result.success()
        val auth = AuthRepositories.default as? SupabaseAuthRepository ?: return Result.success()
        val token = try { auth.tokenAndUserId()?.first } catch (e: Exception) { null } ?: return Result.success()
        val rest = SupabaseRest(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
        return try {
            val last = PushPrefs.lastId(ctx)
            if (last < 0) {
                val r = rest.call("GET", "notifications?select=id&order=id.desc&limit=1", bearer = token)
                if (!r.ok) return Result.retry()
                PushPrefs.setLastId(ctx, PushLogic.maxId(r.body) ?: 0L)
                return Result.success()
            }
            val r = rest.call("GET", "notifications?select=id,title,body&id=gt.$last&order=id.asc&limit=5", bearer = token)
            if (!r.ok) return Result.retry()
            val items = PushLogic.parse(r.body)
            if (items.isNotEmpty() && !PushNotifier.hasPermission(ctx)) return Result.success() // لا نتقدم حتى يُمنح الإذن
            items.forEach { PushNotifier.show(ctx, it); PushPrefs.setLastId(ctx, it.id) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
