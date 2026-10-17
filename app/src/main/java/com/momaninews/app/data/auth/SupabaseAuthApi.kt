package com.momaninews.app.data.auth

import com.momaninews.app.data.model.User
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** خطأ HTTP بدون أي محتوى للاستجابة (قد تحوي رموزاً). */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** طلبات Supabase Auth/REST بالمفتاح العام. لا تسجّل شيئاً. تُستدعى من خيط الإدخال/الإخراج. */
class SupabaseAuthApi(supabaseUrl: String, private val publicKey: String) {
    private val base = supabaseUrl.trimEnd('/')

    init {
        require(base.startsWith("https://")) { "Supabase URL must use https" }
    }

    fun exchangeGoogleIdToken(idToken: String, rawNonce: String, nowSec: Long): AuthSession {
        val body = JSONObject()
            .put("provider", "google")
            .put("id_token", idToken)
            .put("nonce", rawNonce)
            .toString()
        return SupabaseAuthParser.parseTokenResponse(
            request("POST", "/auth/v1/token?grant_type=id_token", body, null), nowSec
        )
    }

    fun refresh(refreshToken: String, nowSec: Long): AuthSession {
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        return SupabaseAuthParser.parseTokenResponse(
            request("POST", "/auth/v1/token?grant_type=refresh_token", body, null), nowSec
        )
    }

    fun fetchProfile(session: AuthSession): User? {
        val path = "/rest/v1/users?select=id,email,display_name,verification,role,is_banned&id=eq.${session.userId}&limit=1"
        return SupabaseAuthParser.parseProfile(request("GET", path, null, session.accessToken))
    }

    fun logout(accessToken: String) {
        request("POST", "/auth/v1/logout", null, accessToken)
    }

    private fun request(method: String, path: String, jsonBody: String?, bearer: String?): String {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", publicKey)
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (jsonBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            val stream = connection.inputStream
            return stream.use { input ->
                val bytes = input.readNBytesLimited(1024 * 1024)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesLimited(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = read(buffer)
            if (n == -1) break
            total += n
            if (total > max) throw IOException("Response too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
