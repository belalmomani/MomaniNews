package com.momaninews.app.data.posts

import android.content.Context
import android.net.Uri
import com.momaninews.app.BuildConfig
import com.momaninews.app.data.admin.NewsImagePrep
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.Nonce
import com.momaninews.app.data.auth.SupabaseAuthRepository
import com.momaninews.app.data.remote.SupabaseNewsParser
import com.momaninews.app.data.remote.SupabaseRest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PostException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_SIGNED_IN, INVALID, BAD_IMAGE, TOO_MANY_PENDING, TOO_MANY_TODAY, FAILED }
}

data class MyPost(val title: String, val statusLabel: String, val dateLabel: String)

/** قواعد نقية قابلة للاختبار. */
object PostRules {
    const val MAX_TEXT = 5000
    const val MAX_TITLE = 100
    const val MAX_IMAGE_SIDE = 1600
    const val MAX_IMAGE_BYTES = 2_500_000   // أقل من حد الحاوية (3MB)

    /** النص بعد القص، أو null إن كان فارغاً أو أطول من الحد. */
    fun cleanText(text: String): String? {
        val t = text.trim()
        return if (t.isEmpty() || t.length > MAX_TEXT) null else t
    }

    /** العنوان = أول سطر غير فارغ مقصوصاً إلى 100 حرف (بقطع عند آخر مسافة إن أمكن). */
    fun titleOf(text: String): String {
        val first = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        if (first.length <= MAX_TITLE) return first
        val cut = first.take(MAX_TITLE)
        val space = cut.lastIndexOf(' ')
        return (if (space >= MAX_TITLE / 2) cut.substring(0, space) else cut).trimEnd() + "…"
    }

    fun statusLabel(status: String) = when (status) {
        "approved" -> "منشور"
        "rejected" -> "لم يُنشر"
        "archived" -> "مؤرشف"
        else -> "بانتظار اعتماد المدير"
    }

    /** مسار الصورة داخل حاوية user-posts: مجلد المستخدم ثم اسم عشوائي. */
    fun imagePath(userId: String, random: String) = "$userId/$random.jpg"

    fun publicImageUrl(supabaseUrl: String, path: String) =
        supabaseUrl.trimEnd('/') + "/storage/v1/object/public/user-posts/" + path

    fun parseMine(json: String): List<MyPost> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            MyPost(
                title = r.optString("title", ""),
                statusLabel = statusLabel(r.optString("status", "pending")),
                dateLabel = SupabaseNewsParser.formatDate(if (r.isNull("created_at")) "" else r.optString("created_at", ""))
            )
        }
    }
}

class PostsRepository(
    private val supabaseUrl: String,
    private val publicKey: String,
    private val session: suspend () -> Pair<String, String>?
) {
    private val rest = SupabaseRest(supabaseUrl, publicKey)

    suspend fun mine(): List<MyPost> = withContext(Dispatchers.IO) {
        val (token, _) = session() ?: throw PostException(PostException.Reason.NOT_SIGNED_IN)
        val r = rest.call("POST", "rpc/my_posts", "{}", bearer = token)
        if (!r.ok) throw IOException("HTTP ${r.code}")
        PostRules.parseMine(r.body)
    }

    /** يرسل منشوراً بحالة «بانتظار الاعتماد». الصورة اختيارية (تُصغَّر وتُرمَّز JPEG وتُرفع إلى مجلد المستخدم). */
    suspend fun submit(context: Context, text: String, regionId: String, image: Uri?) = withContext(Dispatchers.IO) {
        val clean = PostRules.cleanText(text) ?: throw PostException(PostException.Reason.INVALID)
        if (regionId.isBlank()) throw PostException(PostException.Reason.INVALID)
        val (token, uid) = session() ?: throw PostException(PostException.Reason.NOT_SIGNED_IN)

        // الاسم المعروض يجب أن يطابق اسمه في القاعدة (تفرضه السياسة)
        val prof = rest.call("GET", "public_profiles?select=display_name&id=eq.$uid&limit=1", bearer = token)
        val name = try { JSONArray(prof.body).optJSONObject(0)?.optString("display_name", "") ?: "" } catch (e: Exception) { "" }
        if (!prof.ok || name.isBlank()) throw PostException(PostException.Reason.FAILED)

        var imageUrl: String? = null
        if (image != null) {
            val jpeg = NewsImagePrep.toJpeg(context, image, PostRules.MAX_IMAGE_SIDE, PostRules.MAX_IMAGE_BYTES)
                ?: throw PostException(PostException.Reason.BAD_IMAGE)
            val path = PostRules.imagePath(uid, Nonce.random().take(32))
            uploadJpeg(path, jpeg, token)
            imageUrl = PostRules.publicImageUrl(supabaseUrl, path)
        }

        val json = JSONObject()
            .put("title", PostRules.titleOf(clean))
            .put("content", clean)
            .put("region_id", regionId)
            .put("author_id", uid)
            .put("source_name", name)
        imageUrl?.let { json.put("image_url", it) }
        val r = rest.call("POST", "news", json.toString(), bearer = token, prefer = "return=minimal")
        if (r.code == 400 || r.code == 409) {
            if (r.body.contains("too_many_pending_posts")) throw PostException(PostException.Reason.TOO_MANY_PENDING)
            if (r.body.contains("too_many_posts_today")) throw PostException(PostException.Reason.TOO_MANY_TODAY)
        }
        if (!r.ok) throw PostException(PostException.Reason.FAILED)
    }

    private fun uploadJpeg(path: String, bytes: ByteArray, token: String) {
        val c = URL(supabaseUrl.trimEnd('/') + "/storage/v1/object/user-posts/" + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes.size)
            c.setRequestProperty("apikey", publicKey)
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "image/jpeg")
            c.setRequestProperty("x-upsert", "false")
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode !in 200..299) throw PostException(PostException.Reason.FAILED)
        } finally {
            c.disconnect()
        }
    }
}

object PostsRepositories {
    val default: PostsRepository? by lazy {
        val auth = AuthRepositories.default
        if (auth is SupabaseAuthRepository && BuildConfig.SUPABASE_URL.startsWith("https://")) {
            PostsRepository(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) { auth.tokenAndUserId() }
        } else null
    }
}
