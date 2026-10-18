package com.momaninews.app.data.remote

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.repository.NewsRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
private const val PAGE_SIZE = 50

/**
 * يقرأ الأخبار من Supabase (PostgREST) عبر HTTPS بالمفتاح العام فقط، بدون مكتبة خارجية.
 * ما يُرجَع يحدده RLS في القاعدة: الأخبار المعتمدة والمنشورة فقط. لا يُسجَّل أي محتوى أو مفتاح.
 * المفاتيح الجديدة (sb_publishable_...) ليست JWT، لذلك تُرسل في ترويسة apikey فقط دون Authorization.
 */
class SupabaseNewsRepository(url: String, private val publicKey: String) : NewsRepository {

    private val base: String = url.trimEnd('/') + "/rest/v1/"

    init {
        require(base.startsWith("https://")) { "Supabase URL must use https" }
        require(publicKey.isNotBlank()) { "Supabase key is empty" }
    }

    override suspend fun getNews(): List<NewsItem> = withContext(Dispatchers.IO) {
        val body = get("news?select=${SupabaseNewsParser.SELECT}&order=published_at.desc&limit=$PAGE_SIZE")
        SupabaseNewsParser.parseList(body)
    }

    override val pageSize: Int get() = PAGE_SIZE

    override suspend fun getNews(offset: Int): List<NewsItem> = withContext(Dispatchers.IO) {
        val off = offset.coerceAtLeast(0)
        val body = get("news?select=${SupabaseNewsParser.SELECT}&order=published_at.desc&limit=$PAGE_SIZE&offset=$off")
        SupabaseNewsParser.parseList(body)
    }

    override suspend fun getNewsById(id: Int): NewsItem? = withContext(Dispatchers.IO) {
        val body = get("news?select=${SupabaseNewsParser.SELECT}&id=eq.$id&limit=1")
        SupabaseNewsParser.parseFirstOrNull(body)
    }

    private fun get(path: String): String {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", publicKey)

            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IOException("Response too large")
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
