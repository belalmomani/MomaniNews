package com.momaninews.app.data.verification

import com.momaninews.app.data.auth.Nonce
import com.momaninews.app.data.remote.SupabaseRest
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * طلب التوثيق: رفع صورة الهوية إلى حاوية خاصة داخل مجلد المستخدم، ثم تسجيل الطلب.
 * القواعد (المجلد، الحجم، النوع، طلب معلّق واحد) تفرضها القاعدة؛ هذا الكود لا يعتمد عليه الأمان.
 * الصورة الموثّقة لا تظهر لأحد غير الأدمن ولا تُخزَّن على الجهاز.
 */
class SupabaseVerificationRepository(
    private val supabaseUrl: String,
    private val publicKey: String,
    private val rest: SupabaseRest,
    private val session: suspend () -> Pair<String, String>? // (accessToken, userId) أو null
) {
    suspend fun latest(): VerificationRequestInfo? = withContext(Dispatchers.IO) {
        val (token, uid) = session() ?: throw VerificationException(VerificationException.Reason.NOT_SIGNED_IN)
        val r = rest.call(
            "GET",
            "verification_requests?select=status,admin_note,created_at&user_id=eq.$uid&order=created_at.desc&limit=1",
            bearer = token
        )
        if (!r.ok) throw VerificationException(VerificationException.Reason.FAILED)
        SupabaseVerificationParser.parseLatest(r.body)
    }

    /** [jpeg] صورة JPEG مُعدّة مسبقاً (مصغّرة ومُعاد ترميزها). */
    suspend fun submit(fullName: String, jpeg: ByteArray) = withContext(Dispatchers.IO) {
        val name = VerificationRules.cleanName(fullName)
            ?: throw VerificationException(VerificationException.Reason.INVALID_NAME)
        if (jpeg.isEmpty() || jpeg.size > VerificationRules.MAX_IMAGE_BYTES) {
            throw VerificationException(VerificationException.Reason.INVALID_IMAGE)
        }
        val (token, uid) = session() ?: throw VerificationException(VerificationException.Reason.NOT_SIGNED_IN)

        val path = "$uid/${Nonce.random().take(32)}.jpg"
        upload(path, jpeg, token)

        val r = rest.call(
            "POST", "verification_requests",
            JSONObject().put("full_name", name).put("document_path", path).toString(),
            bearer = token, prefer = "return=minimal"
        )
        if (r.code == 409 || r.code == 403) throw VerificationException(VerificationException.Reason.ALREADY_PENDING)
        if (!r.ok) throw VerificationException(VerificationException.Reason.FAILED)
    }

    private fun upload(path: String, bytes: ByteArray, token: String) {
        val url = supabaseUrl.trimEnd('/') + "/storage/v1/object/verification-docs/" + path
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes.size)
            c.setRequestProperty("apikey", publicKey)
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Content-Type", "image/jpeg")
            c.setRequestProperty("x-upsert", "false")
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode !in 200..299) throw VerificationException(VerificationException.Reason.FAILED)
        } finally {
            c.disconnect()
        }
    }
}
