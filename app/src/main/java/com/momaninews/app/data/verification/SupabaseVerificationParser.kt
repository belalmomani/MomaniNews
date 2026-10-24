package com.momaninews.app.data.verification

import com.momaninews.app.data.model.VerificationStatus
import com.momaninews.app.data.remote.SupabaseNewsParser
import org.json.JSONArray

object SupabaseVerificationParser {
    /** آخر طلب (الاستعلام مرتب تنازلياً بـ limit=1). null إن لم يقدّم المستخدم أي طلب. */
    fun parseLatest(json: String): VerificationRequestInfo? {
        val array = JSONArray(json)
        if (array.length() == 0) return null
        val row = array.getJSONObject(0)
        return VerificationRequestInfo(
            status = when (row.optString("status")) {
                "approved" -> VerificationStatus.APPROVED
                "rejected" -> VerificationStatus.REJECTED
                "pending" -> VerificationStatus.PENDING
                else -> VerificationStatus.NONE
            },
            adminNote = if (row.isNull("admin_note")) "" else row.optString("admin_note", ""),
            dateLabel = SupabaseNewsParser.formatDate(if (row.isNull("created_at")) "" else row.optString("created_at", ""))
        )
    }
}
