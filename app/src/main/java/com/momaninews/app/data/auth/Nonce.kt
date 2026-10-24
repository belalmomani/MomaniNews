package com.momaninews.app.data.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * nonce لتدفق Google → Supabase: يُرسَل لـ Google الـ SHA-256 (hex)، ويُرسَل لـ Supabase النص الأصلي،
 * فيقارنه Supabase بالقيمة داخل رمز Google ويمنع إعادة استخدام الرمز.
 */
object Nonce {
    fun random(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
