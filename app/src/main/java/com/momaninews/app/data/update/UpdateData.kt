package com.momaninews.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sha256Url: String,
    val sizeBytes: Long
)

/** قواعد نقية قابلة للاختبار (بلا أندرويد). */
object UpdateLogic {
    const val REPO = "belalmomani/MomaniNews"
    const val TAG_PREFIX = "build-"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val ALLOWED_REDIRECT_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val SHA_RE = Regex("^[0-9a-fA-F]{64}")

    val latestReleaseUrl: String get() = "https://api.github.com/repos/$REPO/releases/latest"

    /** رقم الإصدار من الوسم «build-<رقم>»؛ null لأي صيغة أخرى. */
    fun codeFromTag(tag: String): Long? =
        if (tag.startsWith(TAG_PREFIX)) tag.removePrefix(TAG_PREFIX).toLongOrNull()?.takeIf { it > 0 } else null

    private fun parse(url: String): URI? = try { URI(url) } catch (e: Exception) { null }

    /** رابط ملف الإصدار الأول: https على github.com ومن مسار إصدارات هذا المستودع فقط، بلا بيانات مستخدم. */
    fun isTrustedAssetUrl(url: String): Boolean {
        val u = parse(url) ?: return false
        return u.scheme == "https" && u.host?.lowercase() == "github.com" && u.userInfo == null &&
            (u.path ?: "").startsWith("/$REPO/releases/download/")
    }

    /** أي تحويل (redirect) أثناء التنزيل: https وإلى نطاقات GitHub المعروفة فقط. */
    fun isAllowedRedirect(url: String): Boolean {
        val u = parse(url) ?: return false
        return u.scheme == "https" && u.userInfo == null && (u.host?.lowercase()?.let { it in ALLOWED_REDIRECT_HOSTS } == true)
    }

    /** يُرجع معلومات التحديث إن كان أحدث من [currentCode] ومكتمل الملفات (apk + sha256)، وإلا null. */
    fun parseRelease(json: String, currentCode: Long): UpdateInfo? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name", "")
        val code = codeFromTag(tag) ?: return null
        if (code <= currentCode) return null
        val assets = o.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sha: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val url = a.optString("browser_download_url", "")
            if (!isTrustedAssetUrl(url)) continue
            if (name.endsWith(".apk") && apk == null) apk = a
            if (name.endsWith(".apk.sha256") && sha == null) sha = a
        }
        if (apk == null || sha == null) return null
        return UpdateInfo(
            versionCode = code,
            versionName = o.optString("name", "").ifBlank { tag },
            notes = o.optString("body", "").trim().take(1500),
            apkUrl = apk.getString("browser_download_url"),
            sha256Url = sha.getString("browser_download_url"),
            sizeBytes = apk.optLong("size", -1L)
        )
    }

    /** بصمة SHA-256 من ملف نصي بصيغة sha256sum («<64 hex>  اسم») أو بصمة مجردة. */
    fun parseSha256(text: String): String? = SHA_RE.find(text.trim())?.value?.lowercase()

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

enum class InstallResult { STARTED, NEEDS_PERMISSION, FAILED }

object UpdateRepository {
    private const val UA = "MomaniNews-App"

    /** يتتبع التحويلات يدوياً ويتحقق من كل خطوة (https ونطاقات GitHub فقط). */
    private fun open(first: String): HttpURLConnection {
        var current = first
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/octet-stream, application/vnd.github+json, text/plain")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: run { c.disconnect(); throw IOException("redirect without location") }
                c.disconnect()
                val next = URL(URL(current), loc).toString()
                if (!UpdateLogic.isAllowedRedirect(next)) throw IOException("redirect not allowed")
                current = next
            } else {
                if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code") }
                return c
            }
        }
        throw IOException("too many redirects")
    }

    private fun readText(url: String, maxBytes: Int): String {
        val c = open(url)
        try {
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    if (out.size() + n > maxBytes) throw IOException("response too large")
                    out.write(buf, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally { c.disconnect() }
    }

    /** آخر إصدار منشور إن كان أحدث من المثبّت، وإلا null. */
    suspend fun check(currentCode: Long): UpdateInfo? = withContext(Dispatchers.IO) {
        UpdateLogic.parseRelease(readText(UpdateLogic.latestReleaseUrl, 512 * 1024), currentCode)
    }

    /**
     * ينزّل الـ APK إلى مجلد التخزين المؤقت للتطبيق، ويتحقق من: البصمة SHA-256 المنشورة مع الإصدار،
     * ثم أن الحزمة هي تطبيقنا (packageName) ورقم نسختها أحدث من المثبّتة. توقيع التطبيق يتحقق منه أندرويد نفسه عند التثبيت.
     */
    suspend fun download(context: Context, info: UpdateInfo, currentCode: Long, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            if (!UpdateLogic.isTrustedAssetUrl(info.apkUrl) || !UpdateLogic.isTrustedAssetUrl(info.sha256Url)) throw IOException("untrusted url")
            val expected = UpdateLogic.parseSha256(readText(info.sha256Url, 4096)) ?: throw IOException("bad checksum file")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "MomaniNews-${info.versionCode}.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            val c = open(info.apkUrl)
            try {
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
                if (total > UpdateLogic.MAX_APK_BYTES) throw IOException("file too large")
                var read = 0L
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            read += n
                            if (read > UpdateLogic.MAX_APK_BYTES) throw IOException("file too large")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            onProgress(read, total)
                        }
                    }
                }
            } catch (e: Exception) {
                file.delete(); throw e
            } finally { c.disconnect() }
            if (UpdateLogic.hex(digest.digest()) != expected) { file.delete(); throw IOException("checksum mismatch") }
            val pi = context.packageManager.getPackageArchiveInfo(file.path, 0)
            if (pi == null || pi.packageName != context.packageName || PackageInfoCompat.getLongVersionCode(pi) <= currentCode) {
                file.delete(); throw IOException("package mismatch")
            }
            file
        }

    /** يفتح مثبّت النظام للملف. يحتاج إذن «تثبيت التطبيقات» للتطبيق مرة واحدة (يوجّه المستخدم لإعداده). */
    fun install(context: Context, file: File): InstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return InstallResult.NEEDS_PERMISSION
        }
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            InstallResult.STARTED
        } catch (e: Exception) {
            InstallResult.FAILED
        }
    }
}
