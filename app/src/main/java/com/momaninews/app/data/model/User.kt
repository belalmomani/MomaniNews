package com.momaninews.app.data.model

enum class VerificationStatus { NONE, PENDING, APPROVED, REJECTED }

data class User(
    val id: String,
    val displayName: String,
    val email: String,
    val verification: VerificationStatus = VerificationStatus.NONE,
    /** true للحساب التجريبي المحلي فقط؛ لا يوجد مثله على الخادم. */
    val isDemo: Boolean = false
)
