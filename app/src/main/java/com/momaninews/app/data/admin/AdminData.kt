package com.momaninews.app.data.admin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.momaninews.app.BuildConfig
import com.momaninews.app.data.auth.Nonce
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

data class KeywordInput(val kind: String, val word: String, val regionId: String?, val needsContext: Boolean)

object AdminRules {
    val KEYWORD_KINDS = listOf("region", "alias", "name", "context", "exclude")
    fun kindLabel(kind: String) = when (kind) {
        "region" -> "منطقة"; "alias" -> "اسم بديل"; "name" -> "اسم شخص"; "context" -> "سياق"; "exclude" -> "استبعاد"; else -> kind
    }
    private val REGION_IDS = setOf("abeen", "abelin", "sakhra", "momani")
    /** «النوع الكلمة [المنطقة] [ctx]»؛ المنطقة إلزامية لـ region/alias، وctx تعني «تحتاج سياق». */
    fun parseKeywordInput(text: String): KeywordInput? {
        val parts = text.trim().split(Regex("\\s+")).toMutableList()
        if (parts.size < 2) return null
        val kind = parts.removeAt(0).lowercase()
        if (kind !in KEYWORD_KINDS) return null
        val ctx = parts.remove("ctx")
        val region = parts.lastOrNull()?.takeIf { it in REGION_IDS }?.also { parts.removeAt(parts.size - 1) }
        val word = parts.joinToString(" ").trim()
        if (word.isEmpty() || word.length > 80) return null
        if ((kind == "region" || kind == "alias") && region == null) return null
        return KeywordInput(kind, word, region, ctx)
    }
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

    const val MAX_VIDEO_BYTES = 50L * 1024 * 1024 // حد الحاوية في القاعدة (الخطة المجانية)

    /** نوع MIME ← امتداد الملف. نفس الأنواع المسموحة في حاوية news-media (انظر migration 20261017140000). */
    private val VIDEO_EXT = mapOf(
        "video/mp4" to "mp4", "video/webm" to "webm", "video/3gpp" to "3gp",
        "video/quicktime" to "mov", "video/x-matroska" to "mkv"
    )
    private val IMAGE_EXT = mapOf("image/png" to "png", "image/webp" to "webp", "image/gif" to "gif", "image/jpeg" to "jpg")
    private val CONTENT_TYPE = (VIDEO_EXT.entries + IMAGE_EXT.entries).associate { it.value to it.key }

    fun videoExt(mime: String?): String? = VIDEO_EXT[mime]
    fun imageExt(mime: String?): String? = IMAGE_EXT[mime]
    fun contentTypeFor(ext: String): String? = CONTENT_TYPE[ext]

    /** مسار ملف وسائط: شهر/اسم عشوائي.امتداد. [random] مُمرَّر للاختبار. */
    fun mediaPath(yearMonth: String, random: String, ext: String): String {
        require(yearMonth.matches(Regex("[0-9]{6}")) && random.matches(Regex("[A-Za-z0-9_-]{8,64}")) && ext in CONTENT_TYPE.keys)
        return "$yearMonth/$random.$ext"
    }

    fun publicMediaUrl(base: String, path: String): String =
        base.trimEnd('/') + "/storage/v1/object/public/news-media/" + path

    /** true إن كانت استجابة return=representation تحوي صفاً واحداً على الأقل. */
    fun affectedRows(json: String): Boolean = runCatching { JSONArray(json).length() > 0 }.getOrDefault(false)

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
                body = listOf(str(r, "excerpt"), fetchInfo(r)).filter { it.isNotBlank() }.joinToString("\n\n"),
                extra = str(r, "excerpt")
            )
        }
    }

    /** معلومات الأخبار المجلوبة تلقائياً: الكلمات المطابقة والرابط الأصلي وملاحظة الجلب. */
    private fun fetchInfo(r: JSONObject): String {
        val terms = r.optJSONArray("matched_terms")
        val words = if (terms == null) "" else (0 until terms.length()).joinToString("، ") { terms.optString(it) }
        val parts = mutableListOf<String>()
        if (words.isNotBlank()) parts += "الكلمات المطابقة: $words"
        str(r, "original_url").takeIf { it.isNotBlank() }?.let { parts += "المصدر الأصلي: $it" }
        str(r, "fetch_note").takeIf { it.isNotBlank() }?.let { parts += "ملاحظة الجلب: $it" }
        return parts.joinToString("\n")
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

    /**
     * PATCH/DELETE تُرجع نجاحاً حتى لو لم تطابق أي صف (مثلاً رفضتها سياسة RLS بصمت)، لذلك نطلب الصفوف المتأثرة
     * ونعدّها خطأً إن كانت صفراً. POST يكتفي بالنجاح.
     */
    private suspend fun write(method: String, path: String, body: JSONObject?) = withContext(Dispatchers.IO) {
        val verify = method == "PATCH" || method == "DELETE"
        val r = rest.call(method, path, body?.toString(), bearer = token(), prefer = if (verify) "return=representation" else "return=minimal")
        if (!r.ok) throw IOException("HTTP ${r.code}")
        if (verify && !AdminRules.affectedRows(r.body)) throw IOException("لم يتأثر أي صف (لا صلاحية أو الصف تغيّر)")
    }

    // ---- الأخبار
    suspend fun pendingNews() = AdminParser.news(get("news?select=id,title,excerpt,source_name,created_at,matched_terms,original_url,fetch_note,regions(name_ar)&status=eq.pending&order=created_at.asc&limit=100"))
    suspend fun allNews() = AdminParser.news(get("news?select=id,title,excerpt,source_name,created_at,regions(name_ar)&status=neq.pending&order=created_at.desc&limit=50"))
    suspend fun setNewsStatus(ref: String, status: String) {
        require(status in listOf("approved", "rejected", "archived"))
        write("PATCH", "news?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("status", status))
    }
    /** رفض مع سبب يُحفظ في review_note (يراه المشرفون فقط). */
    suspend fun rejectNews(ref: String, reason: String) {
        val note = reason.trim().take(500)
        write("PATCH", "news?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("status", "rejected").put("review_note", if (note.isEmpty()) JSONObject.NULL else note))
    }
    /** تعديل العنوان/الملخص المعروضين قبل الاعتماد. */
    suspend fun editNewsText(ref: String, title: String?, excerpt: String?) {
        val body = JSONObject()
        title?.let { val t = AdminRules.cleanText(it, 300); require(t.length >= 5) { "العنوان قصير" }; body.put("title", t) }
        excerpt?.let { body.put("excerpt", it.trim().take(600)) }
        write("PATCH", "news?id=eq.${AdminRules.safeId(ref)}", body)
    }
    suspend fun fetchRuns(): List<AdminItem> {
        val a = JSONArray(get("fetch_runs?select=id,started_at,trigger,sources_total,sources_failed,items_seen,items_matched,items_inserted,items_duplicate,error_summary&order=started_at.desc&limit=30"))
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            AdminItem(
                ref = r.get("id").toString(),
                title = "جلب " + (if (r.optString("trigger") == "manual") "يدوي" else "مجدول") + " — " + SupabaseNewsParser.formatDate(r.optString("started_at")) + " " + r.optString("started_at").drop(11).take(5),
                meta = "مصادر ${r.optInt("sources_total")} (فشل ${r.optInt("sources_failed")}) • فُحص ${r.optInt("items_seen")} • مطابق ${r.optInt("items_matched")} • جديد ${r.optInt("items_inserted")} • مكرر ${r.optInt("items_duplicate")}",
                body = if (r.isNull("error_summary")) "" else r.optString("error_summary"),
                flag = r.optInt("sources_failed") > 0
            )
        }
    }
    suspend fun sourceStatus(): List<AdminItem> {
        val a = JSONArray(get("sources?select=id,name,url,kind,is_active,last_status,last_error,last_success_at&order=name.asc&limit=100"))
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            val st = if (r.isNull("last_status")) "لم يُجلب بعد" else r.optString("last_status")
            AdminItem(
                ref = r.get("id").toString(),
                title = r.optString("name") + if (r.optBoolean("is_active", true)) "" else " (معطّل)",
                meta = "${r.optString("kind")} • $st" + (if (r.isNull("last_success_at")) "" else " • آخر نجاح " + SupabaseNewsParser.formatDate(r.optString("last_success_at"))),
                body = (if (r.isNull("last_error")) "" else "الخطأ: " + r.optString("last_error") + "\n") + r.optString("url"),
                flag = !r.isNull("last_error")
            )
        }
    }
    // ---- الكلمات المفتاحية لمحرك الجلب
    suspend fun keywords(): List<AdminItem> {
        val a = JSONArray(get("keywords?select=id,word,kind,region_id,needs_context,is_active&order=kind.asc,word.asc&limit=300"))
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            val active = r.optBoolean("is_active", true)
            AdminItem(
                ref = r.get("id").toString(),
                title = r.optString("word") + if (active) "" else " (معطّلة)",
                meta = AdminRules.kindLabel(r.optString("kind")) + (if (r.isNull("region_id")) "" else " • " + r.optString("region_id")) + (if (r.optBoolean("needs_context")) " • تحتاج سياق" else ""),
                flag = active
            )
        }
    }
    /** الإدخال: «النوع الكلمة [رقم المنطقة]» مثل: region عبين abeen */
    suspend fun addKeyword(input: String) {
        val p = AdminRules.parseKeywordInput(input) ?: throw IOException("الصيغة: النوع الكلمة [المنطقة] — النوع: region/alias/name/context/exclude")
        val body = JSONObject().put("word", p.word).put("kind", p.kind).put("needs_context", p.needsContext)
        if (p.regionId != null) body.put("region_id", p.regionId)
        write("POST", "keywords", body)
    }
    suspend fun setKeywordActive(ref: String, active: Boolean) =
        write("PATCH", "keywords?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("is_active", active))
    suspend fun deleteKeyword(ref: String) = write("DELETE", "keywords?id=eq.${AdminRules.safeId(ref)}", null)
    suspend fun deleteNews(ref: String) = write("DELETE", "news?id=eq.${AdminRules.safeId(ref)}", null)

    suspend fun addNews(title: String, excerpt: String, content: String, source: String, regionId: String, publish: Boolean, imageUrl: String? = null, videoUrl: String? = null) {
        require(ADMIN_REGIONS.any { it.first == regionId })
        val t = AdminRules.cleanText(title, 300); val c = content.trim()
        require(t.isNotEmpty() && c.isNotEmpty())
        val row = JSONObject().put("title", t).put("content", c).put("region_id", regionId)
            .put("status", if (publish) "approved" else "pending")
        AdminRules.cleanText(excerpt, 600).takeIf { it.isNotEmpty() }?.let { row.put("excerpt", it) }
        AdminRules.cleanText(source, 120).takeIf { it.isNotEmpty() }?.let { row.put("source_name", it) }
        imageUrl?.let { require(it.startsWith("https://")); row.put("image_url", it) }
        videoUrl?.let { require(it.startsWith("https://")); row.put("video_url", it) }
        write("POST", "news", row)
    }

    /**
     * يرفع أي صورة يستطيع الهاتف قراءتها (PNG/JPEG/WebP/HEIC/...) بأي حجم:
     * GIF يُرفع كما هو (لتبقى الحركة)، والباقي يُصغَّر إلى 2560 بكسل كحد أقصى ويُرمَّز JPEG (تُحذف بيانات الموقع).
     * يُرجع الرابط العام.
     */
    suspend fun uploadImage(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ym = java.time.LocalDate.now().toString().take(7).replace("-", "")
        val mime = resolver.getType(uri)
        if (mime == "image/gif") {
            val size = querySize(context, uri)
            require(size in 1..AdminRules.MAX_VIDEO_BYTES) { "gif size" }
            val path = AdminRules.mediaPath(ym, Nonce.random().take(32), "gif")
            upload(path, "image/gif", size) { out ->
                (resolver.openInputStream(uri) ?: throw IOException("cannot open image")).use { it.copyTo(out, 64 * 1024) }
            }
            return@withContext AdminRules.publicMediaUrl(supabaseUrl, path)
        }
        val jpeg = NewsImagePrep.toJpeg(context, uri) ?: throw IllegalArgumentException("bad image")
        val path = AdminRules.mediaPath(ym, Nonce.random().take(32), "jpg")
        upload(path, "image/jpeg", jpeg.size.toLong()) { it.write(jpeg) }
        AdminRules.publicMediaUrl(supabaseUrl, path)
    }

    /** يرفع فيديو (MP4/WebM/3GP/MOV/MKV) حتى 50MB بالبث دون تحميله كله في الذاكرة. */
    suspend fun uploadVideo(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ext = AdminRules.videoExt(resolver.getType(uri)) ?: throw IllegalArgumentException("video type")
        val size = querySize(context, uri)
        require(size in 1..AdminRules.MAX_VIDEO_BYTES) { "video size" }
        val path = AdminRules.mediaPath(java.time.LocalDate.now().toString().take(7).replace("-", ""), Nonce.random().take(32), ext)
        upload(path, AdminRules.contentTypeFor(ext)!!, size) { out ->
            (resolver.openInputStream(uri) ?: throw IOException("cannot open video")).use { it.copyTo(out, 64 * 1024) }
        }
        AdminRules.publicMediaUrl(supabaseUrl, path)
    }

    private fun querySize(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L

    private suspend fun upload(path: String, contentType: String, length: Long, writer: (java.io.OutputStream) -> Unit) {
        val bearer = token()
        val c = URL(supabaseUrl.trimEnd('/') + "/storage/v1/object/news-media/" + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 120_000
            c.instanceFollowRedirects = false
            c.doOutput = true
            c.setFixedLengthStreamingMode(length)
            c.setRequestProperty("apikey", publicKey)
            c.setRequestProperty("Authorization", "Bearer $bearer")
            c.setRequestProperty("Content-Type", contentType)
            c.setRequestProperty("x-upsert", "false")
            c.outputStream.use { writer(it) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        } finally {
            c.disconnect()
        }
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
