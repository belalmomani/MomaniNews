package com.momaninews.app.data.auth

import com.momaninews.app.data.model.User
import com.momaninews.app.data.model.VerificationStatus
import org.json.JSONArray
import org.json.JSONObject

/** جلسة Supabase. تحتوي رموزاً: لا تُسجَّل في أي سجل ولا تُعرض، وتُخزَّن مشفّرة فقط. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    /** وقت انتهاء رمز الوصول (ثوانٍ منذ 1970). */
    val expiresAtSec: Long,
    val userId: String,
    val email: String
) {
    fun isExpired(nowSec: Long, skewSec: Long = 60): Boolean = nowSec >= expiresAtSec - skewSec

    fun toJson(): String = JSONObject()
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("expires_at", expiresAtSec)
        .put("user_id", userId)
        .put("email", email)
        .toString()

    // لا نطبع الرموز أبداً حتى بالخطأ.
    override fun toString(): String = "AuthSession(user=$userId)"
}

object SupabaseAuthParser {

    /** استجابة /auth/v1/token (id_token أو refresh_token). */
    fun parseTokenResponse(json: String, nowSec: Long): AuthSession {
        val obj = JSONObject(json)
        val user = obj.getJSONObject("user")
        val expiresIn = obj.optLong("expires_in", 3600L)
        return AuthSession(
            accessToken = obj.getString("access_token"),
            refreshToken = obj.getString("refresh_token"),
            expiresAtSec = nowSec + expiresIn,
            userId = user.getString("id"),
            email = if (user.isNull("email")) "" else user.optString("email", "")
        )
    }

    fun parseStored(json: String): AuthSession {
        val obj = JSONObject(json)
        return AuthSession(
            accessToken = obj.getString("access_token"),
            refreshToken = obj.getString("refresh_token"),
            expiresAtSec = obj.getLong("expires_at"),
            userId = obj.getString("user_id"),
            email = obj.optString("email", "")
        )
    }

    /** استجابة /rest/v1/users?select=id,email,display_name,verification. تُرجع null إن لم يوجد الصف. */
    fun parseProfile(json: String): User? {
        val array = JSONArray(json)
        if (array.length() == 0) return null
        val row = array.getJSONObject(0)
        return User(
            id = row.getString("id"),
            displayName = row.optString("display_name", "").ifEmpty { "مستخدم" },
            email = if (row.isNull("email")) "" else row.optString("email", ""),
            verification = when (row.optString("verification", "none")) {
                "pending" -> VerificationStatus.PENDING
                "approved" -> VerificationStatus.APPROVED
                "rejected" -> VerificationStatus.REJECTED
                else -> VerificationStatus.NONE
            },
            isDemo = false
        )
    }
}
