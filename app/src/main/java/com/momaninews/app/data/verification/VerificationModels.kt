package com.momaninews.app.data.verification

import com.momaninews.app.data.model.VerificationStatus

/** آخر طلب توثيق للمستخدم. [adminNote] سبب الرفض إن وُجد. */
data class VerificationRequestInfo(
    val status: VerificationStatus,
    val adminNote: String,
    val dateLabel: String
)

class VerificationException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_SIGNED_IN, INVALID_NAME, INVALID_IMAGE, ALREADY_PENDING, FAILED }
}

object VerificationRules {
    const val MIN_NAME = 2
    const val MAX_NAME = 120
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024 // نفس حد الحاوية في القاعدة
    const val MAX_IMAGE_SIDE = 1600

    /** الاسم بعد القص، أو null إذا كان قصيراً أو طويلاً. */
    fun cleanName(raw: String): String? {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        return if (t.length in MIN_NAME..MAX_NAME) t else null
    }

    /** معامل التصغير (قوة 2) كي لا يتجاوز أكبر ضلع [maxSide] تقريباً عند فك الصورة. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_IMAGE_SIDE): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxSide) {
            longest /= 2
            sample *= 2
        }
        return sample
    }
}
