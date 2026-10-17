package com.momaninews.app.data.admin

import com.momaninews.app.BuildConfig
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.SupabaseAuthRepository
import com.momaninews.app.data.remote.SupabaseNewsParser
import com.momaninews.app.data.remote.SupabaseRest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** عنصر عام في قوائم الإدارة. [ref] معرّف الصف (رقم أو uuid) و[extra] قيمة إضافية (مسار مستند، دور، إلخ). */
data class AdminItem(
    val ref: String,
    val title: String,
    val meta: String,
    val body: String = "",
    val extra: String = "",
    val flag: Boolean = false
)

val ADMIN_REGIONS = listOf("abeen" to "عبين", "abelin" to "عبلين", "sakhra" to "صخرة")

object AdminRules {
    /** مدة الإعلان المسموحة بالأيام (نفس لوحة الويب). null إن كانت غير صالحة. */
    fun parseDays(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 1..365 }

    /** (بداية، نهاية) بصيغة ISO من لحظة البدء والمدة. */
    fun adWindow(now: Instant, days: Int): Pair<String, String> =
        now.toString() to now.plusSeconds(days * 86_400L).toString()

    /** تمديد: من نهاية الإعلان الحالية أو من الآن أيهما أبعد. */
    fun extendedEnd(now: Instant, currentEnd: Instant?, days: Int): String {
        val base = if (currentEnd != null && currentEnd.isAfter(now)) currentEnd else now
        return base.plusSeconds(days * 86_400L).toString()
    }

    fun cleanText(text: String, max: Int): String = text.trim().take(max)

    /** معرّف رقمي آمن للاستعلام (يمنع حقن معاملات في الرابط). */
    fun safeId(ref: String): String {
        require(ref.matches(Regex("[0-9]{1,18}"))) { "bad id" }
        return ref
    }

    fun safeUuid(ref: String): String {
        require(ref.matches(Regex("[0-9a-fA-F-]{36}"))) { "bad uuid" }
        return ref
    }
}

object AdminParser {
    private fun str(row: JSONObject, key: String) = if (row.isNull(key)) "" else row.optString(key, "")
    private fun date(row: JSONObject, key: String) = SupabaseNewsParser.formatDate(str(row, key))

    fun news(json: String): List<AdminItem> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            val region = r.optJSONObject("regions")?.optString("name_ar", "") ?: ""
            AdminItem(
                ref = r.get("id").toString(),
                title = str(r, "title"),
                meta = listOf(region, str(r, "source_name"), date(r, "created_at")).filter { it.isNotBlank() }.joinToString(" • "),
                body = str(r, "excerpt")
            )
        }
    }

    fun comments(json: String): List<AdminItem> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            val hidden = str(r, "status") == "hidden"
            AdminItem(
                ref = r.get("id").toString(),
                title = str(r, "body"),
                meta = (if (hidden) "مخفي" else "ظاهر") + " • خبر #" + str(r, "news_id") + " • " + date(r, "created_at"),
                flag = hidden
            )
        }
    }

    fun verifications(json: String): List<AdminItem> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            AdminItem(
                ref = r.get("id").toString(),
                title = str(r, "full_name"),
                meta = date(r, "created_at"),
                extra = str(r, "document_path")
            )
        }
    }

    fun ads(json: String): List<AdminItem> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            val ends = str(r, "ends_at")
            AdminItem(
                ref = r.get("id").toString(),
                title = str(r, "title"),
                meta = listOf(str(r, "contact"), if (ends.isNotEmpty()) "حتى " + date(r, "ends_at") else date(r, "created_at")).filter { it.isNotBlank() }.joinToString(" • "),
                body = str(r, "body"),
                extra = ends
            )
        }
    }

    fun users(json: String): List<AdminItem> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            val role = str(r, "role")
            val banned = r.optBoolean("is_banned", false)
            AdminItem(
                ref = str(r, "id"),
                title = str(r, "display_name").ifEmpty { "مستخدم" } + (if (role == "admin") " (مدير)" else "") + (if (banned) " — محظور" else ""),
                meta = str(r, "email"),
                extra = role,
                flag = banned
            )
        }
    }

    /** الرابط الموقّع المُرجَع من التخزين يكون نسبياً ("/object/sign/...") ويُضاف إليه /storage/v1. */
    fun signedUrl(base: String, json: String): String? {
        val rel = JSONObject(json).optString("signedURL", "")
        if (!rel.startsWith("/object/sign/")) return null
        return base.trimEnd('/') + "/storage/v1" + rel
    }
}

/** كل طلبات الإدارة تذهب بصلاحية المستخدم؛ إن لم يكن أدمن ترفضها القاعدة (42501 / قائمة فارغة). */
class AdminRepository(
    private val supabaseUrl: String,
    private val publicKey: String,
    private val session: suspend () -> Pair<String, String>?
) {
    private val rest = SupabaseRest(supabaseUrl, publicKey)

    private suspend fun token(): String = session()?.first ?: throw IOException("not signed in")

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val r = rest.call("GET", path, bearer = token())
        if (!r.ok) throw IOException("HTTP ${r.code}")
        r.body
    }

    private suspend fun write(method: String, path: String, body: JSONObject?) = withContext(Dispatchers.IO) {
        val r = rest.call(method, path, body?.toString(), bearer = token(), prefer = "return=minimal")
        if (!r.ok) throw IOException("HTTP ${r.code}")
    }

    // ---- الأخبار
    suspend fun pendingNews() = AdminParser.news(get("news?select=id,title,excerpt,source_name,created_at,regions(name_ar)&status=eq.pending&order=created_at.asc&limit=100"))
    suspend fun allNews() = AdminParser.news(get("news?select=id,title,excerpt,source_name,created_at,regions(name_ar)&status=neq.pending&order=created_at.desc&limit=50"))
    suspend fun setNewsStatus(ref: String, status: String) {
        require(status in listOf("approved", "rejected", "archived"))
        write("PATCH", "news?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("status", status))
    }
    suspend fun deleteNews(ref: String) = write("DELETE", "news?id=eq.${AdminRules.safeId(ref)}", null)

    suspend fun addNews(title: String, excerpt: String, content: String, source: String, regionId: String, publish: Boolean) {
        require(ADMIN_REGIONS.any { it.first == regionId })
        val t = AdminRules.cleanText(title, 300); val c = content.trim()
        require(t.isNotEmpty() && c.isNotEmpty())
        val row = JSONObject().put("title", t).put("content", c).put("region_id", regionId)
            .put("status", if (publish) "approved" else "pending")
        AdminRules.cleanText(excerpt, 600).takeIf { it.isNotEmpty() }?.let { row.put("excerpt", it) }
        AdminRules.cleanText(source, 120).takeIf { it.isNotEmpty() }?.let { row.put("source_name", it) }
        write("POST", "news", row)
    }

    // ---- التعليقات
    suspend fun comments() = AdminParser.comments(get("comments?select=id,body,status,created_at,news_id,user_id&order=created_at.desc&limit=50"))
    suspend fun setCommentHidden(ref: String, hidden: Boolean) =
        write("PATCH", "comments?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("status", if (hidden) "hidden" else "visible"))
    suspend fun deleteComment(ref: String) = write("DELETE", "comments?id=eq.${AdminRules.safeId(ref)}", null)

    // ---- التوثيق
    suspend fun pendingVerifications() = AdminParser.verifications(get("verification_requests?select=id,user_id,full_name,document_path,created_at&status=eq.pending&order=created_at.asc&limit=100"))
    suspend fun setVerification(ref: String, approved: Boolean) =
        write("PATCH", "verification_requests?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("status", if (approved) "approved" else "rejected"))

    /** رابط موقّع قصير العمر (5 دقائق) لصورة الهوية؛ الحاوية خاصة. */
    suspend fun documentUrl(path: String): String = withContext(Dispatchers.IO) {
        val encoded = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val c = URL(supabaseUrl.trimEnd('/') + "/storage/v1/object/sign/verification-docs/" + encoded).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("apikey", publicKey)
            c.setRequestProperty("Authorization", "Bearer " + token())
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write("""{"expiresIn":300}""".toByteArray()) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            AdminParser.signedUrl(supabaseUrl, body) ?: throw IOException("bad signed url")
        } finally {
            c.disconnect()
        }
    }

    // ---- الإعلانات
    suspend fun pendingAds() = AdminParser.ads(get("advertisements?select=id,title,body,contact,created_at&status=eq.pending&order=created_at.asc&limit=100"))
    suspend fun approvedAds() = AdminParser.ads(get("advertisements?select=id,title,body,contact,created_at,ends_at&status=eq.approved&order=ends_at.desc&limit=100"))
    suspend fun approveAd(ref: String, days: Int) {
        val (s, e) = AdminRules.adWindow(Instant.now(), days)
        write("PATCH", "advertisements?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("status", "approved").put("starts_at", s).put("ends_at", e))
    }
    suspend fun rejectAd(ref: String, note: String) {
        val row = JSONObject().put("status", "rejected")
        AdminRules.cleanText(note, 500).takeIf { it.isNotEmpty() }?.let { row.put("admin_note", it) }
        write("PATCH", "advertisements?id=eq.${AdminRules.safeId(ref)}", row)
    }
    suspend fun extendAd(item: AdminItem, days: Int) {
        val current = runCatching { Instant.parse(item.extra) }.getOrNull()
        write("PATCH", "advertisements?id=eq.${AdminRules.safeId(item.ref)}", JSONObject().put("ends_at", AdminRules.extendedEnd(Instant.now(), current, days)))
    }
    suspend fun deleteAd(ref: String) = write("DELETE", "advertisements?id=eq.${AdminRules.safeId(ref)}", null)

    // ---- المستخدمون
    suspend fun users() = AdminParser.users(get("users?select=id,display_name,email,role,is_banned&order=created_at.desc&limit=100"))
    suspend fun setUserFlags(uuid: String, role: String, banned: Boolean) {
        require(role == "admin" || role == "user")
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("p_user", AdminRules.safeUuid(uuid)).put("p_role", role).put("p_banned", banned)
            val r = rest.call("POST", "rpc/admin_set_user_flags", body.toString(), bearer = token())
            if (!r.ok) throw IOException("HTTP ${r.code}")
        }
    }

    // ---- إشعار عام
    suspend fun broadcast(title: String, body: String, newsId: Int?) {
        val t = AdminRules.cleanText(title, 120); require(t.isNotEmpty())
        val row = JSONObject().put("title", t)
        AdminRules.cleanText(body, 500).takeIf { it.isNotEmpty() }?.let { row.put("body", it) }
        if (newsId != null && newsId > 0) row.put("news_id", newsId)
        write("POST", "notifications", row)
    }
}

object AdminRepositories {
    val default: AdminRepository? by lazy {
        val auth = AuthRepositories.default
        if (auth is SupabaseAuthRepository) {
            AdminRepository(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) { auth.tokenAndUserId() }
        } else null
    }
}
