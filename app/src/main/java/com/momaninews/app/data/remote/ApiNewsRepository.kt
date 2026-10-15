package com.momaninews.app.data.remote

import com.momaninews.app.data.model.NewsItem
import com.momaninews.app.data.repository.NewsRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
private const val PAGE_SIZE = 50

/**
 * يقرأ الأخبار من واجهة API عبر HTTPS. لا يحمل أي سر، ولا يسجّل أي محتوى للاستجابات أو الرموز.
 * في هذه المرحلة تُجلب الصفحة الأولى فقط ويُصفّى محلياً؛ الترقيم والبحث من الخادم لاحقاً.
 */
class ApiNewsRepository(baseUrl: String) : NewsRepository {

    private val base: String = baseUrl.trimEnd('/') + "/"

    init {
        require(base.startsWith("https://")) { "API base URL must use https" }
    }

    override suspend fun getNews(): List<NewsItem> = withContext(Dispatchers.IO) {
        val body = get("news?page=1&per_page=$PAGE_SIZE")
            ?: throw IOException("Unexpected 404 for news list")
        NewsJsonParser.parseList(body)
    }

    override suspend fun getNewsById(id: Int): NewsItem? = withContext(Dispatchers.IO) {
        val body = get("news/$id") ?: return@withContext null
        NewsJsonParser.parseDetail(body)
    }

    /** يُرجع النص، أو null عند 404. أي رمز آخر غير ناجح يُسقط IOException. */
    private fun get(path: String): String? {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false // يمنع التحويل غير المقصود إلى http
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) throw IOException("HTTP $code")

            return connection.inputStream.use { readLimited(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String {
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
        return output.toString(Charsets.UTF_8.name())
    }
}
