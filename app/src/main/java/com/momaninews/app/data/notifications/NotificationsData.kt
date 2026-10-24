package com.momaninews.app.data.notifications

import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.SupabaseAuthRepository
import com.momaninews.app.data.remote.SupabaseNewsParser
import com.momaninews.app.data.remote.SupabaseRest
import com.momaninews.app.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AppNotification(
    val id: Int,
    val title: String,
    val body: String,
    val newsId: Int?,
    val dateLabel: String,
    /** إشعار عام للجميع (لا يُعلَّم مقروءاً). */
    val isBroadcast: Boolean,
    val isUnread: Boolean
)

object NotificationsParser {
    fun parse(json: String): List<AppNotification> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val row = array.getJSONObject(i)
            val broadcast = row.isNull("user_id")
            AppNotification(
                id = row.getInt("id"),
                title = row.getString("title"),
                body = if (row.isNull("body")) "" else row.optString("body", ""),
                newsId = if (row.isNull("news_id")) null else row.getInt("news_id"),
                dateLabel = SupabaseNewsParser.formatDate(if (row.isNull("created_at")) "" else row.optString("created_at", "")),
                isBroadcast = broadcast,
                isUnread = !broadcast && row.isNull("read_at")
            )
        }
    }
}

/** إشعارات المستخدم + العامة (تقرّر القاعدة ما يراه). */
class NotificationsRepository(
    private val rest: SupabaseRest,
    private val session: suspend () -> Pair<String, String>?
) {
    suspend fun list(): List<AppNotification> = withContext(Dispatchers.IO) {
        val (token, _) = session() ?: throw IOException("not signed in")
        val r = rest.call(
            "GET",
            "notifications?select=id,user_id,title,body,news_id,read_at,created_at&order=created_at.desc&limit=50",
            bearer = token
        )
        if (!r.ok) throw IOException("HTTP ${r.code}")
        NotificationsParser.parse(r.body)
    }

    /** يعلّم إشعارات المستخدم الشخصية غير المقروءة كمقروءة (أفضل جهد). */
    suspend fun markPersonalRead() = withContext(Dispatchers.IO) {
        val (token, uid) = session() ?: return@withContext
        val now = java.time.Instant.now().toString()
        rest.call(
            "PATCH", "notifications?user_id=eq.$uid&read_at=is.null",
            JSONObject().put("read_at", now).toString(), bearer = token, prefer = "return=minimal"
        )
    }
}

object NotificationsRepositories {
    val default: NotificationsRepository? by lazy {
        val auth = AuthRepositories.default
        if (auth is SupabaseAuthRepository) {
            NotificationsRepository(SupabaseRest(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)) { auth.tokenAndUserId() }
        } else null
    }
}
