package com.momaninews.app.data.auth

import com.momaninews.app.data.model.VerificationStatus
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthLogicTest {

    private val tokenJson = """
        {"access_token":"AT","refresh_token":"RT","expires_in":3600,"token_type":"bearer",
         "user":{"id":"11111111-2222-3333-4444-555555555555","email":"a@example.com"}}
    """.trimIndent()

    @Test
    fun nonce_sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Nonce.sha256Hex("abc")
        )
    }

    @Test
    fun nonce_randomIsLongAndUnique() {
        val a = Nonce.random()
        val b = Nonce.random()
        assertEquals(64, a.length)
        assertNotEquals(a, b)
    }

    @Test
    fun tokenResponse_parsesAndComputesExpiry() {
        val s = SupabaseAuthParser.parseTokenResponse(tokenJson, nowSec = 1000)
        assertEquals("AT", s.accessToken)
        assertEquals("RT", s.refreshToken)
        assertEquals(4600, s.expiresAtSec)
        assertEquals("a@example.com", s.email)
    }

    @Test
    fun session_expiryUsesSkew() {
        val s = SupabaseAuthParser.parseTokenResponse(tokenJson, nowSec = 0) // ينتهي عند 3600
        assertFalse(s.isExpired(nowSec = 3000))
        assertTrue(s.isExpired(nowSec = 3541)) // خلال هامش الدقيقة
    }

    @Test
    fun session_storedRoundTrip() {
        val s = SupabaseAuthParser.parseTokenResponse(tokenJson, nowSec = 5)
        assertEquals(s, SupabaseAuthParser.parseStored(s.toJson()))
    }

    @Test
    fun session_toStringDoesNotLeakTokens() {
        val text = SupabaseAuthParser.parseTokenResponse(tokenJson, nowSec = 0).toString()
        assertFalse(text.contains("AT") && text.contains("RT"))
        assertFalse(text.contains("a@example.com"))
    }

    @Test
    fun tokenResponse_errorBodyThrows() {
        assertThrows(JSONException::class.java) {
            SupabaseAuthParser.parseTokenResponse("""{"error":"invalid_grant"}""", 0)
        }
    }

    @Test
    fun profile_mapsVerificationAndFallbacks() {
        val u = SupabaseAuthParser.parseProfile(
            """[{"id":"x","email":null,"display_name":"علي","verification":"approved"}]"""
        )!!
        assertEquals("علي", u.displayName)
        assertEquals("", u.email)
        assertEquals(VerificationStatus.APPROVED, u.verification)
        assertFalse(u.isDemo)
    }

    @Test
    fun profile_unknownVerificationIsNone_emptyArrayIsNull() {
        val u = SupabaseAuthParser.parseProfile(
            """[{"id":"x","email":"e","display_name":"","verification":"weird"}]"""
        )!!
        assertEquals(VerificationStatus.NONE, u.verification)
        assertEquals("مستخدم", u.displayName)
        assertNull(SupabaseAuthParser.parseProfile("[]"))
    }
}
