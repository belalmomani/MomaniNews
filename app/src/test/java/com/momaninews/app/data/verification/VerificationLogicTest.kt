package com.momaninews.app.data.verification

import com.momaninews.app.data.model.VerificationStatus
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VerificationLogicTest {

    @Test
    fun cleanName_trimsCollapsesAndBounds() {
        assertEquals("علي أحمد", VerificationRules.cleanName("  علي    أحمد  "))
        assertNull(VerificationRules.cleanName("ع"))
        assertNull(VerificationRules.cleanName("   "))
        assertNull(VerificationRules.cleanName("ا".repeat(VerificationRules.MAX_NAME + 1)))
        assertEquals("ا".repeat(VerificationRules.MAX_NAME), VerificationRules.cleanName("ا".repeat(VerificationRules.MAX_NAME)))
    }

    @Test
    fun sampleSize_isPowerOfTwoAndKeepsSideNearMax() {
        assertEquals(1, VerificationRules.sampleSize(1200, 800))
        assertEquals(1, VerificationRules.sampleSize(1600, 1000))
        assertEquals(2, VerificationRules.sampleSize(3200, 2000))
        assertEquals(2, VerificationRules.sampleSize(4000, 3000)) // 2000 بكسل، لا يصغّر تحت 1600
        assertEquals(4, VerificationRules.sampleSize(12000, 9000)) // 3000 بكسل
    }

    @Test
    fun parseLatest_mapsFieldsAndEmptyIsNull() {
        val info = SupabaseVerificationParser.parseLatest(
            """[{"status":"rejected","admin_note":"صورة غير واضحة","created_at":"2026-10-14T09:00:00+00:00"}]"""
        )!!
        assertEquals(VerificationStatus.REJECTED, info.status)
        assertEquals("صورة غير واضحة", info.adminNote)
        assertNull(SupabaseVerificationParser.parseLatest("[]"))
    }

    @Test
    fun parseLatest_nullNoteAndUnknownStatus() {
        val info = SupabaseVerificationParser.parseLatest(
            """[{"status":"weird","admin_note":null,"created_at":null}]"""
        )!!
        assertEquals(VerificationStatus.NONE, info.status)
        assertEquals("", info.adminNote)
        assertEquals("", info.dateLabel)
    }

    @Test
    fun parseLatest_errorObjectThrows() {
        assertThrows(JSONException::class.java) { SupabaseVerificationParser.parseLatest("""{"message":"x"}""") }
    }
}
