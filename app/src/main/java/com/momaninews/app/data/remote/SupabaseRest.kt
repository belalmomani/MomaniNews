package com.momaninews.app.data.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RestResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

/**
 * عميل REST صغير لـ Supabase (PostgREST) بالمفتاح العام، مع رمز المستخدم اختيارياً.
 * لا يسجّل شيئاً. يُرجع الاستجابة كما هي ليقرر المستدعي (أجسام الأخطاء لا تحوي أسراراً لكنها لا تُعرض للمستخدم).
 * يُستدعى من خيط الإدخال/الإخراج.
 */
class SupabaseRest(supabaseUrl: String, private val publicKey: String) {
    private val base = supabaseUrl.trimEnd('/') + "/rest/v1/"

    init {
        require(base.startsWith("https://")) { "Supabase URL must use https" }
    }

    fun call(
        method: String,
        path: String,
        jsonBody: String? = null,
        bearer: String? = null,
        prefer: String? = null
    ): RestResponse {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", publicKey)
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (prefer != null) connection.setRequestProperty("Prefer", prefer)
            if (jsonBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { readLimited(it) } ?: ""
            return RestResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            total += n
            if (total > 1024 * 1024) throw IOException("Response too large")
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
