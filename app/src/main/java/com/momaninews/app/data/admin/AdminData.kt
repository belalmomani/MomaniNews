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
    val flag: Boolean = false,
    /** اسم الناشر/المصدر (للأخبار)؛ يُستخدم لحظر الناشر من بطاقة الخبر. */
    val source: String = ""
)

val ADMIN_REGIONS = listOf("abeen" to "عبين", "abelin" to "عبلين", "sakhra" to "صخرة", "momani" to "أخبار المومني")

data class KeywordInput(val kind: String, val word: String, val regionId: String?, val needsContext: Boolean)

object AdminRules {
    /** حالة الإعلان المعتمد: موقوف / منتهٍ / ساري («» إن لم يُعتمد بعد). */
    fun adState(paused: Boolean, endsAt: String, now: Instant): String {
        if (endsAt.isEmpty()) return ""
        if (paused) return "موقوف"
        val end = runCatching { Instant.parse(endsAt) }.getOrNull() ?: return ""
        return if (end.isBefore(now)) "منتهٍ" else "ساري"
    }
    /** «الاسم | رابط https» → (الاسم، الرابط)؛ null إن كان غير صالح. */
    fun parseSourceInput(text: String): Pair<String, String>? {
        val i = text.indexOf('|')
        if (i < 0) return null
        val name = text.substring(0, i).trim()
        val url = text.substring(i + 1).trim()
        if (name.isEmpty() || name.length > 120 || !url.startsWith("https://") || url.length < 12 || url.any { it.isWhitespace() }) return null
        return name to url
    }
    val KEYWORD_KINDS = listOf("region", "alias", "name", "context", "exclude")
    fun kindLabel(kind: String) = when (kind) {
        "region" -> "منطقة"; "alias" -> "اسم بديل"; "name" -> "اسم شخص"; "context" -> "سياق"; "exclude" -> "استبعاد"; else -> kind
    }
    private val REGION_IDS = setOf("abeen", "abelin", "sakhra", "momani")
    private val REGION_NAMES = mapOf(
        "عبين" to "abeen", "عبلين" to "abelin", "صخرة" to "sakhra", "صخره" to "sakhra",
        "المومني" to "momani", "مومني" to "momani", "أخبار المومني" to "momani"
    )
    private val KIND_NAMES = mapOf(
        "منطقة" to "region", "اسم_بديل" to "alias", "بديل" to "alias", "اسم" to "name",
        "سياق" to "context", "استبعاد" to "exclude", "استبعد" to "exclude"
    )
    /**
     * «النوع الكلمة [المنطقة] [ctx]». النوع بالإنجليزية (region/alias/name/context/exclude) أو بالعربية (منطقة/بديل/اسم/سياق/استبعاد).
     * المنطقة (abeen/abelin/sakhra/momani) إلزامية لـ region/alias، وتُستنتج من الكلمة إن كانت اسم منطقة (عبين/عبلين/صخرة).
     * ctx تعني «تحتاج سياقاً».
     */
    fun parseKeywordInput(text: String): KeywordInput? {
        val parts = text.trim().split(Regex("\\s+")).toMutableList()
        if (parts.size < 2) return null
        val first = parts.removeAt(0)
        val kind = (KIND_NAMES[first] ?: first.lowercase())
        if (kind !in KEYWORD_KINDS) return null
        val ctx = parts.remove("ctx")
        var region = parts.lastOrNull()?.takeIf { it in REGION_IDS }?.also { parts.removeAt(parts.size - 1) }
        val word = parts.joinToString(" ").trim()
        if (word.isEmpty() || word.length > 80) return null
        if ((kind == "region" || kind == "alias") && region == null) region = REGION_NAMES[word]
        if ((kind == "region" || kind == "alias") && region == null) return null
        return KeywordInput(kind, word, region, ctx)
    }

    /** قيمة ناشر صالحة للحظر: 2..120 حرفاً بعد القص وتوحيد الفراغات، وبلا رموز تحكم؛ وإلا null. */
    fun cleanPublisher(text: String?): String? {
        val v = text.orEmpty().replace(Regex("\\s+"), " ").trim()
        return if (v.length in 2..120 && v.none { it.isISOControl() }) v else null
    }

    /** تسمية تصنيف الخبر (من الجالب)؛ فارغ لغير المصنّف أو لقيمة غير معروفة. */
    fun categoryLabel(wire: String): String = when (wire) {
        "death" -> "⚫ وفاة"; "congrats" -> "🎉 تهنئة"; "achievement" -> "🏆 نجاح وتفوق"; "services" -> "🛠 خدمات"
        "sports" -> "⚽ رياضة"; "weather" -> "🌧 طقس"; "incident" -> "🚨 حوادث"; else -> ""
    }

    /**
     * من نموذج الإضافة السهل إلى أسطر بالصيغة القديمة (سطر لكل كلمة). الكلمات مفصولة بسطر جديد أو فاصلة (، ,).
     * المنطقة تُلصق فقط لنوع region/alias إن اختيرت (وإلا تُستنتج من الكلمة). فارغ = لا كلمات.
     */
    fun composeKeywordLines(words: String, kind: String, regionId: String): String =
        words.split(Regex("[\\n\\r،,]")).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { w ->
            val region = if ((kind == "region" || kind == "alias") && regionId in REGION_IDS) " $regionId" else ""
            "$kind $w$region"
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

    /** قائمة معرّفات رقمية لشرط in.(...)؛ ترفض الفارغ وأي قيمة غير رقمية، وتزيل التكرار. */
    fun idList(refs: Collection<String>): String {
        require(refs.isNotEmpty()) { "لا عناصر محددة" }
        return refs.map { safeId(it) }.distinct().joinToString(",", "(", ")")
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
            val userPost = !r.isNull("author_id")
            AdminItem(
                ref = r.get("id").toString(),
                title = str(r, "title"),
                meta = listOf(region, str(r, "source_name"), if (userPost) "منشور مستخدم" else "", AdminRules.categoryLabel(str(r, "category")), if (r.optBoolean("is_featured", false)) "★ مميّز" else "", date(r, "created_at"))
                    .filter { it.isNotBlank() }.joinToString(" • "),
                body = listOf(
                    str(r, "excerpt"),
                    if (userPost) str(r, "content") else "",
                    if (userPost) str(r, "image_url").takeIf { it.isNotBlank() }?.let { "الصورة: $it" } ?: "" else "",
                    fetchInfo(r)
                ).filter { it.isNotBlank() }.joinToString("\n\n"),
                extra = str(r, "excerpt"),
                flag = r.optBoolean("is_featured", false),
                source = str(r, "source_name")
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

    private val STAT_LABELS = listOf(
        "news_pending" to "أخبار بانتظار الاعتماد", "news_approved" to "أخبار منشورة", "news_rejected" to "أخبار مرفوضة",
        "news_fetched" to "أخبار مجلوبة تلقائياً", "news_last_7d" to "منشور في آخر 7 أيام",
        "users_total" to "المستخدمون", "users_new_7d" to "مستخدمون جدد (7 أيام)",
        "comments_visible" to "تعليقات ظاهرة", "comments_reported" to "تعليقات مُبلَّغ عنها",
        "verification_pending" to "طلبات توثيق معلّقة", "ads_pending" to "طلبات إعلان معلّقة", "ads_active" to "إعلانات سارية",
        "ads_views" to "مشاهدات الإعلانات (تقريبي)", "ads_clicks" to "نقرات الإعلانات (تقريبي)",
        "sources_active" to "مصادر مفعّلة", "sources_failing" to "مصادر فاشلة"
    )

    fun stats(json: String): List<AdminItem> {
        val o = JSONObject(json)
        val rows = STAT_LABELS.filter { o.has(it.first) }.map { (k, label) ->
            AdminItem(ref = k, title = label, meta = o.optLong(k).toString(), flag = k.endsWith("failing") && o.optLong(k) > 0)
        }
        val last = if (o.isNull("last_fetch_at")) "لم يُشغَّل بعد" else date(o, "last_fetch_at")
        return rows + AdminItem(ref = "last_fetch_at", title = "آخر جلب للأخبار", meta = last)
    }

    fun flags(json: String): Map<String, String> {
        val a = JSONArray(json)
        return (0 until a.length()).associate { i -> val r = a.getJSONObject(i); r.get("news_id").toString() to str(r, "reason") }
    }

    /** المعلَّمة تظهر أولاً وعليها ⚠ وسبب التحذير؛ الترتيب النسبي للباقي يبقى كما هو. */
    fun markFlagged(items: List<AdminItem>, flags: Map<String, String>): List<AdminItem> {
        if (flags.isEmpty()) return items
        val marked = items.map { n -> flags[n.ref]?.let { n.copy(title = "⚠ " + n.title, meta = "⚠ $it • " + n.meta) } ?: n }
        return marked.filter { it.title.startsWith("⚠ ") } + marked.filterNot { it.title.startsWith("⚠ ") }
    }

    /** نتيجة admin_insights: ثلاث قوائم للعرض + ملخص. */
    class Insights(val summary: List<AdminItem>, val topNews: List<AdminItem>, val sources: List<AdminItem>)

    fun insights(json: String): Insights {
        val o = JSONObject(json)
        val top = o.optJSONArray("top_news") ?: JSONArray()
        val topNews = List(top.length()) { i ->
            val r = top.getJSONObject(i)
            AdminItem(
                ref = r.get("id").toString(), title = str(r, "title"),
                meta = "👁 ${r.optLong("views")} • 👍 ${r.optLong("credible")} • 💬 ${r.optLong("comments")}"
            )
        }
        val src = o.optJSONArray("sources") ?: JSONArray()
        val sources = List(src.length()) { i ->
            val r = src.getJSONObject(i)
            val total = r.optLong("total"); val approved = r.optLong("approved")
            val rate = if (total > 0) " • قبول ${approved * 100 / total}%" else ""
            AdminItem(
                ref = "src$i", title = str(r, "name"),
                meta = "المجموع $total • معتمد $approved • مرفوض ${r.optLong("rejected")} • معلّق ${r.optLong("pending")}$rate",
                flag = r.optLong("rejected") > approved && total > 0
            )
        }
        val p = o.optJSONObject("posts")
        val summary = buildList {
            if (p != null) {
                add(AdminItem("posts_total", "منشورات المستخدمين", p.optLong("total").toString()))
                add(AdminItem("posts_approved", "معتمدة", p.optLong("approved").toString()))
                add(AdminItem("posts_rejected", "مرفوضة", p.optLong("rejected").toString()))
                add(AdminItem("posts_pending", "بانتظار الاعتماد", p.optLong("pending").toString()))
                add(AdminItem("posts_7d", "منشورات آخر 7 أيام", p.optLong("last_7d").toString()))
                val t = p.optLong("total")
                if (t > 0) add(AdminItem("posts_rate", "نسبة قبول المنشورات", "${p.optLong("approved") * 100 / t}%"))
            }
            add(AdminItem("flagged", "أخبار معلّقة فيها كلمة محظورة", o.optLong("flagged_pending").toString(), flag = o.optLong("flagged_pending") > 0))
            add(AdminItem("blocked", "كلمات محظورة", o.optLong("blocked_words").toString()))
            add(AdminItem("hidden", "تعليقات مخفية", o.optLong("comments_hidden").toString()))
        }
        return Insights(summary, topNews, sources)
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
            val paused = r.optBoolean("is_paused", false)
            val state = AdminRules.adState(paused, ends, Instant.now())
            val stats = if (r.has("views")) " • مشاهدات ${r.optLong("views")} • نقرات ${r.optLong("clicks")}" else ""
            AdminItem(
                ref = r.get("id").toString(),
                title = str(r, "title"),
                meta = listOf(state, str(r, "contact"), if (ends.isNotEmpty()) "حتى " + date(r, "ends_at") else date(r, "created_at")).filter { it.isNotBlank() }.joinToString(" • ") + stats,
                body = str(r, "body"),
                extra = ends,
                flag = paused
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
    suspend fun pendingNews(): List<AdminItem> {
        val base = "id,title,excerpt,content,image_url,author_id,source_name,created_at,matched_terms,original_url,fetch_note,regions(name_ar)"
        // category من الترحيل 20261025100000؛ إن لم يُطبَّق بعد نعرض الأخبار بدونه بدل أن يفشل التبويب
        val body = runCatching { get("news?select=$base,category&status=eq.pending&order=created_at.asc&limit=100") }
            .getOrElse { get("news?select=$base&status=eq.pending&order=created_at.asc&limit=100") }
        val items = AdminParser.news(body)
        // التحذيرات (كلمات محظورة) في جدول للأدمن فقط؛ فشل جلبها لا يمنع عرض الأخبار
        val flags = runCatching { AdminParser.flags(get("news_flags?select=news_id,reason&limit=300")) }.getOrDefault(emptyMap())
        return AdminParser.markFlagged(items, flags)
    }
    suspend fun allNews() = AdminParser.news(get("news?select=id,title,excerpt,source_name,author_id,is_featured,created_at,regions(name_ar)&status=neq.pending&order=created_at.desc&limit=50"))
    suspend fun setNewsStatus(ref: String, status: String) {
        require(status in listOf("approved", "rejected", "archived"))
        write("PATCH", "news?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("status", status))
    }
    /** اعتماد/رفض مجموعة أخبار معلّقة دفعة واحدة (على دفعات من 50). تمسّ المعلّق فقط. يعيد عدد الدفعات المنفّذة. */
    suspend fun setNewsStatusBulk(refs: Collection<String>, status: String, reason: String? = null) {
        require(status in listOf("approved", "rejected"))
        val ids = refs.map { AdminRules.safeId(it) }.distinct()
        require(ids.isNotEmpty()) { "لا عناصر محددة" }
        ids.chunked(50).forEach { chunk ->
            val body = JSONObject().put("status", status)
            if (status == "rejected") {
                val note = reason?.trim()?.take(500).orEmpty()
                body.put("review_note", if (note.isEmpty()) JSONObject.NULL else note)
            }
            write("PATCH", "news?id=in.${AdminRules.idList(chunk)}&status=eq.pending", body)
        }
    }
    /** تمييز الخبر في شريط «أبرز الأخبار» أو إلغاء التمييز. */
    suspend fun setNewsFeatured(ref: String, featured: Boolean) =
        write("PATCH", "news?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("is_featured", featured))
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
        val base = "id,started_at,trigger,sources_total,sources_failed,items_seen,items_matched,items_inserted,items_duplicate,error_summary"
        // items_rejected من الترحيل 20261021100000؛ إن لم يُطبَّق بعد نعرض الأعمدة القديمة بدل أن يفشل التبويب
        val a = JSONArray(runCatching { get("fetch_runs?select=$base,items_rejected&order=started_at.desc&limit=30") }
            .getOrElse { get("fetch_runs?select=$base&order=started_at.desc&limit=30") })
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            AdminItem(
                ref = r.get("id").toString(),
                title = "جلب " + (if (r.optString("trigger") == "manual") "يدوي" else "مجدول") + " — " + SupabaseNewsParser.formatDate(r.optString("started_at")) + " " + r.optString("started_at").drop(11).take(5),
                meta = "مصادر ${r.optInt("sources_total")} (فشل ${r.optInt("sources_failed")}) • فُحص ${r.optInt("items_seen")} • مطابق ${r.optInt("items_matched")} • مرفوض ${r.optInt("items_rejected")} • جديد ${r.optInt("items_inserted")} • مكرر ${r.optInt("items_duplicate")}",
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
                flag = r.optBoolean("is_active", true),
                extra = if (r.isNull("last_error")) "" else "err"
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
        val p = AdminRules.parseKeywordInput(input) ?: throw IOException("لم تُفهم الصيغة. اكتب: النوع ثم الكلمة ثم المنطقة. مثال: region مالطا abeen — أو بالعربية: استبعاد قبة الصخرة")
        val body = JSONObject().put("word", p.word).put("kind", p.kind).put("needs_context", p.needsContext)
        if (p.regionId != null) body.put("region_id", p.regionId)
        write("POST", "keywords", body)
    }
    suspend fun setKeywordActive(ref: String, active: Boolean) =
        write("PATCH", "keywords?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("is_active", active))
    suspend fun deleteKeyword(ref: String) = write("DELETE", "keywords?id=eq.${AdminRules.safeId(ref)}", null)
    /** الإدخال: «الاسم | رابط https للخلاصة» */
    suspend fun addSource(input: String) {
        val p = AdminRules.parseSourceInput(input) ?: throw IOException("الصيغة: الاسم | https://رابط-الخلاصة")
        write("POST", "sources", JSONObject().put("name", p.first).put("url", p.second).put("kind", "rss"))
    }
    suspend fun setSourceActive(ref: String, active: Boolean) =
        write("PATCH", "sources?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("is_active", active))
    suspend fun deleteSource(ref: String) = write("DELETE", "sources?id=eq.${AdminRules.safeId(ref)}", null)
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
    suspend fun reportedComments(): List<AdminItem> {
        val reports = JSONArray(get("comment_reports?select=comment_id,reason&order=created_at.desc&limit=200"))
        val counts = linkedMapOf<Long, Int>(); val reasons = mutableMapOf<Long, String>()
        for (i in 0 until reports.length()) {
            val o = reports.getJSONObject(i); val id = o.getLong("comment_id")
            counts[id] = (counts[id] ?: 0) + 1
            o.optString("reason", "").takeIf { it.isNotBlank() && it != "null" }?.let { reasons.putIfAbsent(id, it) }
        }
        if (counts.isEmpty()) return emptyList()
        val ids = counts.keys.joinToString(",") { AdminRules.safeId(it.toString()) }
        val rows = AdminParser.comments(get("comments?select=id,body,status,created_at,news_id,user_id&id=in.($ids)"))
        return rows.sortedByDescending { counts[it.ref.toLongOrNull()] ?: 0 }.map {
            val n = counts[it.ref.toLongOrNull()] ?: 0
            it.copy(meta = "بلاغات: $n" + (reasons[it.ref.toLongOrNull()]?.let { r -> " • $r" } ?: "") + " • " + it.meta)
        }
    }
    suspend fun dismissReports(commentRef: String) = write("DELETE", "comment_reports?comment_id=eq.${AdminRules.safeId(commentRef)}", null)
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
    suspend fun approvedAds() = AdminParser.ads(get("advertisements?select=id,title,body,contact,created_at,ends_at,is_paused,views,clicks&status=eq.approved&order=ends_at.desc&limit=100"))
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
    suspend fun setAdPaused(ref: String, paused: Boolean) =
        write("PATCH", "advertisements?id=eq.${AdminRules.safeId(ref)}", JSONObject().put("is_paused", paused))
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

    // ---- مؤشرات (دالة admin_stats: أرقام حقيقية تُحسب عند الطلب)
    suspend fun stats(): List<AdminItem> = withContext(Dispatchers.IO) {
        val r = rest.call("POST", "rpc/admin_stats", "{}", bearer = token())
        if (!r.ok) throw IOException("HTTP ${r.code}")
        AdminParser.stats(r.body)
    }

    suspend fun insights(): AdminParser.Insights = withContext(Dispatchers.IO) {
        val r = rest.call("POST", "rpc/admin_insights", "{}", bearer = token())
        if (!r.ok) throw IOException("HTTP ${r.code}")
        AdminParser.insights(r.body)
    }

    // ---- كلمات محظورة (للأدمن فقط): خبر معلّق يحويها يُعلَّم بتحذير، وتعليق يحويها يُخفى تلقائياً
    suspend fun blockedWords(): List<AdminItem> {
        val a = JSONArray(get("blocked_words?select=id,word&order=word.asc&limit=300"))
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            AdminItem(ref = r.get("id").toString(), title = r.optString("word"), meta = "")
        }
    }
    suspend fun addBlockedWord(text: String) {
        val w = AdminRules.cleanText(text, 80)
        require(w.length >= 2) { "كلمة قصيرة" }
        write("POST", "blocked_words", JSONObject().put("word", w))
    }
    suspend fun deleteBlockedWord(ref: String) = write("DELETE", "blocked_words?id=eq.${AdminRules.safeId(ref)}", null)

    // ---- ناشرون محظورون (يرفضهم الجالب): نطاق مثل example.com أو اسم ناشر
    suspend fun blockedPublishers(): List<AdminItem> {
        val a = JSONArray(get("blocked_publishers?select=id,value,created_at&order=created_at.desc&limit=300"))
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            AdminItem(ref = r.get("id").toString(), title = r.optString("value"), meta = SupabaseNewsParser.formatDate(if (r.isNull("created_at")) "" else r.optString("created_at", "")))
        }
    }
    /** يضيف ناشراً؛ المكرر (بلا اعتبار للحالة) يُعدّ نجاحاً صامتاً (409). */
    suspend fun addBlockedPublisher(text: String) {
        val v = AdminRules.cleanPublisher(text) ?: throw IllegalArgumentException("قيمة غير صالحة")
        try {
            write("POST", "blocked_publishers", JSONObject().put("value", v))
        } catch (e: IOException) {
            if (e.message?.contains("409") != true) throw e
        }
    }
    suspend fun deleteBlockedPublisher(ref: String) = write("DELETE", "blocked_publishers?id=eq.${AdminRules.safeId(ref)}", null)

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
