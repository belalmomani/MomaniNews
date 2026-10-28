package com.momaninews.app.data.store

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.momaninews.app.BuildConfig
import com.momaninews.app.data.admin.AdminItem
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.SupabaseAuthRepository
import com.momaninews.app.data.remote.SupabaseRest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class StoreApp(
    val id: Int,
    val repo: String,
    val title: String,
    val description: String,
    val category: String,
    val iconUrl: String?,
    val screenshots: List<String>,
    val apkUrl: String?,
    val versionName: String?,
    val sizeBytes: Long?,
    val status: String
)

/** ما يُسحب من GitHub لمستودع واحد (يراجعه المدير قبل الاعتماد). */
data class GitHubAppInfo(
    val repo: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val screenshots: List<String>,
    val apkUrl: String?,
    val versionName: String?,
    val sizeBytes: Long?
)

/** قواعد نقية قابلة للاختبار (بلا أندرويد). */
object StoreLogic {
    data class Cat(val wire: String, val label: String)
    val categories: List<Cat> = listOf(
        Cat("apps", "تطبيقات"), Cat("games", "ألعاب"), Cat("tools", "أدوات"), Cat("news", "أخبار"), Cat("other", "أخرى")
    )
    fun categoryLabel(wire: String?): String = categories.firstOrNull { it.wire == wire }?.label ?: "أخرى"

    private val REPO_RE = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
    private val IMG_EXT = setOf("png", "jpg", "jpeg", "webp")
    const val MAX_SHOTS = 8

    /** «owner/name» من نص حر: owner/name أو رابط github.com/owner/name (مع .git أو مسار إضافي). null إن لم يصحّ. */
    fun parseRepo(input: String): String? {
        var t = input.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("http://") || t.startsWith("https://")) {
            val u = try { URI(t) } catch (e: Exception) { return null }
            if (u.scheme != "https" || u.host?.lowercase() != "github.com" || u.userInfo != null) return null
            t = (u.path ?: "").trim('/')
        }
        val parts = t.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val repo = parts[0] + "/" + parts[1].removeSuffix(".git")
        return repo.takeIf { REPO_RE.matches(it) && !it.startsWith(".") }
    }

    /** رابط التنزيل: https على github.com ومن إصدارات المستودع نفسه فقط، وملف apk. */
    fun isTrustedApk(url: String?, repo: String): Boolean {
        if (url.isNullOrBlank()) return false
        val u = try { URI(url) } catch (e: Exception) { return false }
        val path = u.path ?: return false
        return u.scheme == "https" && u.host?.lowercase() == "github.com" && u.userInfo == null &&
            path.lowercase().startsWith("/${repo.lowercase()}/releases/download/") && path.lowercase().endsWith(".apk")
    }

    /** رابط صورة آمن للعرض: https فقط ومن نطاقات GitHub المعروفة. */
    fun isTrustedImage(url: String?): Boolean {
        if (url.isNullOrBlank() || url.length > 1000) return false
        val u = try { URI(url) } catch (e: Exception) { return false }
        val h = u.host?.lowercase() ?: return false
        return u.scheme == "https" && u.userInfo == null &&
            (h == "raw.githubusercontent.com" || h == "github.com" || h == "avatars.githubusercontent.com" || h.endsWith(".githubusercontent.com"))
    }

    fun formatSize(bytes: Long?): String = when {
        bytes == null || bytes <= 0 -> ""
        bytes >= 1024L * 1024 -> "%.1f ميغابايت".format(java.util.Locale.US, bytes / 1024.0 / 1024.0)
        else -> "${bytes / 1024} كيلوبايت"
    }

    fun fileName(app: StoreApp): String {
        val base = app.repo.substringAfter('/').replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val v = (app.versionName ?: "").replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return if (v.isEmpty()) "$base.apk" else "$base-$v.apk"
    }

    /** تطبيقات الفئة (null = الكل). */
    fun filter(items: List<StoreApp>, category: String?): List<StoreApp> =
        if (category == null) items else items.filter { it.category == category }

    // ---- JSON من Supabase
    fun parseApps(json: String): List<StoreApp> {
        val a = JSONArray(json)
        return List(a.length()) { i ->
            val r = a.getJSONObject(i)
            val shots = r.optJSONArray("screenshots")
            StoreApp(
                id = r.getInt("id"),
                repo = r.getString("repo"),
                title = r.getString("title"),
                description = if (r.isNull("description")) "" else r.optString("description", ""),
                category = r.optString("category", "other"),
                iconUrl = if (r.isNull("icon_url")) null else r.optString("icon_url").takeIf { isTrustedImage(it) },
                screenshots = if (shots == null) emptyList() else List(shots.length()) { shots.optString(it, "") }.filter { isTrustedImage(it) },
                apkUrl = if (r.isNull("apk_url")) null else r.optString("apk_url"),
                versionName = if (r.isNull("version_name")) null else r.optString("version_name"),
                sizeBytes = if (r.isNull("size_bytes")) null else r.optLong("size_bytes"),
                status = r.optString("status", "pending")
            )
        }
    }

    // ---- استجابات GitHub API
    /** من /releases/latest: أول ملف apk موثوق، واسم الإصدار. */
    fun parseLatestApk(json: String, repo: String): Triple<String, Long?, String?>? {
        val o = JSONObject(json)
        if (o.optBoolean("draft")) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url", "")
            if (a.optString("name", "").endsWith(".apk") && isTrustedApk(url, repo)) {
                val size = a.optLong("size", -1L).takeIf { it > 0 }
                val v = o.optString("name", "").ifBlank { o.optString("tag_name", "") }.trim().take(60).ifBlank { null }
                return Triple(url, size, v)
            }
        }
        return null
    }

    /**
     * من محتويات المجلد store/ في المستودع: الأيقونة icon.* ولقطات الشاشة screenshot*.* (حتى 8، بترتيب الاسم).
     * الاصطلاح: ضع store/icon.png و store/screenshot1.png ... في مستودع التطبيق.
     */
    fun pickMedia(contentsJson: String): Pair<String?, List<String>> {
        val a = try { JSONArray(contentsJson) } catch (e: Exception) { return null to emptyList() }
        val files = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("type") == "file" && isTrustedImage(it.optString("download_url", "")) }
            .map { it.optString("name", "").lowercase() to it.optString("download_url") }
            .filter { (n, _) -> n.substringAfterLast('.', "") in IMG_EXT }
        val icon = files.firstOrNull { (n, _) -> n.substringBeforeLast('.') == "icon" }?.second
        val shots = files.filter { (n, _) -> n.startsWith("screenshot") }.sortedBy { it.first }.map { it.second }.take(MAX_SHOTS)
        return icon to shots
    }

    private val MD_IMAGE = Regex("!\\[[^\\]]*]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)")
    private val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")

    /** أول فقرة نصية من README (بلا عناوين وشارات وصور وHTML) كوصف مبدئي؛ فارغ إن لم توجد. */
    fun readmeSummary(md: String, max: Int = 600): String {
        val paras = mutableListOf<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) paras += cur.toString().trim(); cur = StringBuilder() }
        for (raw in md.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) { flush(); continue }
            if (line.startsWith("#") || line.startsWith("!") || line.startsWith("<") || line.startsWith("```") ||
                line.startsWith("|") || line.startsWith("---") || line.startsWith("[![")) { flush(); continue }
            cur.append(line).append(' ')
        }
        flush()
        val first = paras.firstOrNull() ?: return ""
        return first.replace(MD_LINK, "$1").replace(Regex("[*_`>]+"), "").replace(Regex("\\s+"), " ").trim().take(max)
    }

    /** صور README (png/jpg/webp فقط، لا svg/شارات) كروابط موثوقة؛ النسبية تُحوَّل إلى raw.githubusercontent.com. */
    fun readmeImages(md: String, repo: String): List<String> =
        MD_IMAGE.findAll(md).map { it.groupValues[1] }.mapNotNull { u ->
            val abs = when {
                u.startsWith("https://") -> u
                u.startsWith("http://") || u.contains("://") || u.startsWith("//") -> null
                u.contains("..") -> null
                else -> "https://raw.githubusercontent.com/$repo/HEAD/" + u.removePrefix("./").removePrefix("/")
            } ?: return@mapNotNull null
            val ext = abs.substringBefore('?').substringAfterLast('.', "").lowercase()
            abs.takeIf { ext in IMG_EXT && isTrustedImage(it) }
        }.distinct().take(MAX_SHOTS).toList()

    fun avatarUrl(repo: String): String = "https://github.com/${repo.substringBefore('/')}.png"
}

/** قراءة عامة للتطبيقات المعتمدة (بمفتاح التطبيق العام فقط، تحميها RLS). */
class StoreRepository(private val rest: SupabaseRest) {
    suspend fun list(): List<StoreApp> = withContext(Dispatchers.IO) {
        val r = rest.call(
            "GET",
            "store_apps?select=id,repo,title,description,category,icon_url,screenshots,apk_url,version_name,size_bytes,status&status=eq.approved&order=sort_order.asc,title.asc&limit=200"
        )
        if (!r.ok) throw IOException("HTTP ${r.code}")
        StoreLogic.parseApps(r.body)
    }
}

object StoreRepositories {
    val default: StoreRepository? by lazy {
        if (AuthRepositories.default is SupabaseAuthRepository) StoreRepository(SupabaseRest(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)) else null
    }
}

/** تنزيل مباشر عبر مدير التنزيلات في النظام (بلا أذونات خاصة). التثبيت يتم من إشعار التنزيل بموافقة المستخدم. */
object StoreDownloader {
    /** true = بدأ التنزيل أو فُتح الرابط في المتصفح؛ false = رابط غير موثوق. */
    fun start(context: Context, app: StoreApp): Boolean {
        val url = app.apkUrl
        if (!StoreLogic.isTrustedApk(url, app.repo)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val req = DownloadManager.Request(Uri.parse(url))
                    .setTitle(app.title)
                    .setDescription("تنزيل من متجري")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, StoreLogic.fileName(app))
                dm.enqueue(req)
                return true
            } catch (e: Exception) {
                // نكمل بالمتصفح
            }
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) { false }
    }
}

/** سحب معلومات تطبيق من GitHub (للمدير). واجهة GitHub العامة بلا مفتاح (حد 60 طلباً/ساعة). */
object GitHubStoreSync {
    private const val UA = "MomaniNewsBot"

    private fun getJson(url: String, accept: String = "application/vnd.github+json"): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", accept)
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("GitHub HTTP $code")
            val text = c.inputStream.use { it.readBytes() }
            if (text.size > 512 * 1024) throw IOException("response too large")
            return text.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    suspend fun fetch(repo: String): GitHubAppInfo = withContext(Dispatchers.IO) {
        require(StoreLogic.parseRepo(repo) == repo) { "مستودع غير صالح" }
        val info = getJson("https://api.github.com/repos/$repo") ?: throw IOException("المستودع غير موجود أو خاص")
        val o = JSONObject(info)
        val name = o.optString("name", repo.substringAfter('/'))
        val desc = if (o.isNull("description")) "" else o.optString("description", "").trim().take(2000)
        val rel = getJson("https://api.github.com/repos/$repo/releases/latest")?.let { StoreLogic.parseLatestApk(it, repo) }
        val media = getJson("https://api.github.com/repos/$repo/contents/store")?.let { StoreLogic.pickMedia(it) } ?: (null to emptyList())
        // README: وصف بديل إن لم يكن للمستودع وصف، وصور بديلة إن لم توجد لقطات في store/
        val readme = if (desc.isBlank() || media.second.isEmpty()) runCatching { getJson("https://api.github.com/repos/$repo/readme", "application/vnd.github.raw+json") }.getOrNull() else null
        GitHubAppInfo(
            repo = repo,
            title = name.take(100),
            description = desc.ifBlank { readme?.let { StoreLogic.readmeSummary(it) } ?: "" },
            iconUrl = media.first ?: StoreLogic.avatarUrl(repo),
            screenshots = media.second.ifEmpty { readme?.let { StoreLogic.readmeImages(it, repo) } ?: emptyList() },
            apkUrl = rel?.first,
            versionName = rel?.third,
            sizeBytes = rel?.second
        )
    }
}

/** إدارة المتجر (للمدير فقط؛ RLS تفرض ذلك في القاعدة). */
class StoreAdminRepository(private val rest: SupabaseRest, private val session: suspend () -> Pair<String, String>?) {
    private suspend fun token(): String = session()?.first ?: throw IOException("not signed in")

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val r = rest.call("GET", path, bearer = token())
        if (!r.ok) throw IOException("HTTP ${r.code}")
        r.body
    }

    private suspend fun write(method: String, path: String, body: JSONObject?) = withContext(Dispatchers.IO) {
        val verify = method == "PATCH" || method == "DELETE"
        val r = rest.call(method, path, body?.toString(), bearer = token(), prefer = if (verify) "return=representation" else "return=minimal")
        if (!r.ok) throw IOException("HTTP ${r.code}")
        if (verify && (try { JSONArray(r.body).length() } catch (e: Exception) { 0 }) == 0) throw IOException("لم يتأثر أي صف")
    }

    private fun item(a: StoreApp) = AdminItem(
        ref = a.id.toString(),
        title = a.title,
        meta = listOfNotNull(a.repo, a.versionName, StoreLogic.formatSize(a.sizeBytes).ifEmpty { null }, StoreLogic.categoryLabel(a.category),
            if (a.apkUrl == null) "⚠ بلا ملف apk" else null, if (a.screenshots.isEmpty()) "بلا لقطات" else "${a.screenshots.size} لقطة").joinToString(" · "),
        body = a.description,
        extra = a.description,
        source = a.repo
    )

    suspend fun list(status: String): List<AdminItem> {
        require(status in listOf("pending", "approved", "rejected"))
        val body = get("store_apps?select=id,repo,title,description,category,icon_url,screenshots,apk_url,version_name,size_bytes,status&status=eq.$status&order=created_at.desc&limit=200")
        return StoreLogic.parseApps(body).map { item(it) }
    }

    private fun idOf(ref: String): Int = ref.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("معرّف غير صالح")

    /** يسحب المستودع من GitHub ويضيفه «معلّقاً» للمراجعة. */
    suspend fun addFromGitHub(input: String) {
        val repo = StoreLogic.parseRepo(input) ?: throw IllegalArgumentException("اكتب owner/name أو رابط github.com/owner/name")
        val i = GitHubStoreSync.fetch(repo)
        write("POST", "store_apps", infoJson(i).put("repo", i.repo).put("title", i.title).put("status", "pending"))
    }

    private fun infoJson(i: GitHubAppInfo) = JSONObject()
        .put("description", i.description)
        .put("icon_url", i.iconUrl ?: JSONObject.NULL)
        .put("screenshots", JSONArray(i.screenshots))
        .put("apk_url", i.apkUrl ?: JSONObject.NULL)
        .put("version_name", i.versionName ?: JSONObject.NULL)
        .put("size_bytes", i.sizeBytes ?: JSONObject.NULL)

    /** يحدّث الإصدار والملف والصور من GitHub، ويُبقي الاسم والوصف والتصنيف كما عدّلها المدير. */
    suspend fun refresh(ref: String, repo: String, currentDescription: String = "") {
        val i = GitHubStoreSync.fetch(repo)
        val b = JSONObject()
            .put("icon_url", i.iconUrl ?: JSONObject.NULL)
            .put("screenshots", JSONArray(i.screenshots))
            .put("apk_url", i.apkUrl ?: JSONObject.NULL)
            .put("version_name", i.versionName ?: JSONObject.NULL)
            .put("size_bytes", i.sizeBytes ?: JSONObject.NULL)
        if (currentDescription.isBlank() && i.description.isNotBlank()) b.put("description", i.description)   // لا نكتب فوق وصف عدّله المدير
        write("PATCH", "store_apps?id=eq.${idOf(ref)}", b)
    }

    suspend fun approve(ref: String, category: String) {
        require(StoreLogic.categories.any { it.wire == category }) { "تصنيف غير معروف" }
        write("PATCH", "store_apps?id=eq.${idOf(ref)}", JSONObject().put("status", "approved").put("category", category))
    }
    suspend fun setStatus(ref: String, status: String) {
        require(status in listOf("pending", "rejected"))
        write("PATCH", "store_apps?id=eq.${idOf(ref)}", JSONObject().put("status", status))
    }
    suspend fun setCategory(ref: String, category: String) {
        require(StoreLogic.categories.any { it.wire == category }) { "تصنيف غير معروف" }
        write("PATCH", "store_apps?id=eq.${idOf(ref)}", JSONObject().put("category", category))
    }
    suspend fun setTitle(ref: String, title: String) {
        val t = title.trim().take(100)
        require(t.isNotEmpty()) { "اسم فارغ" }
        write("PATCH", "store_apps?id=eq.${idOf(ref)}", JSONObject().put("title", t))
    }
    suspend fun setDescription(ref: String, text: String) =
        write("PATCH", "store_apps?id=eq.${idOf(ref)}", JSONObject().put("description", text.trim().take(2000)))
    suspend fun delete(ref: String) = write("DELETE", "store_apps?id=eq.${idOf(ref)}", null)
}

object StoreAdminRepositories {
    val default: StoreAdminRepository? by lazy {
        val auth = AuthRepositories.default
        if (auth is SupabaseAuthRepository) StoreAdminRepository(SupabaseRest(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)) { auth.tokenAndUserId() } else null
    }
}
