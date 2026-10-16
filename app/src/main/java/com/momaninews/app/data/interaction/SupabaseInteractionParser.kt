package com.momaninews.app.data.interaction

import com.momaninews.app.data.model.Comment
import com.momaninews.app.data.model.CredibilityVote
import com.momaninews.app.data.remote.SupabaseNewsParser
import org.json.JSONArray
import org.json.JSONObject

/** تحويل استجابات PostgREST الخاصة بالتصويت والتعليقات. أي شكل غير متوقع يُسقط JSONException. */
object SupabaseInteractionParser {

    /** news_vote_counts → (موثوق، غير موثوق). مصفوفة فارغة = (0,0) لخبر بلا أصوات أو غير معتمد. */
    fun parseCounts(json: String): Pair<Int, Int> {
        val array = JSONArray(json)
        if (array.length() == 0) return 0 to 0
        val row = array.getJSONObject(0)
        return row.getInt("credible") to row.getInt("not_credible")
    }

    fun parseMyVote(json: String): CredibilityVote? {
        val array = JSONArray(json)
        if (array.length() == 0) return null
        return voteFromWire(array.getJSONObject(0).getString("vote"))
    }

    fun parseComments(json: String): List<Comment> {
        val array = JSONArray(json)
        return List(array.length()) { i ->
            val row = array.getJSONObject(i)
            Comment(
                id = row.getInt("id"),
                authorName = row.optString("display_name", "").ifEmpty { "مستخدم" },
                text = row.getString("body"),
                dateLabel = SupabaseNewsParser.formatDate(
                    if (row.isNull("created_at")) "" else row.optString("created_at", "")
                ),
                isSample = false
            )
        }
    }

    /** رسالة الخطأ من جسم PostgREST (مثل comment_rate_limited)، أو "" إن لم تُقرأ. */
    fun errorMessage(json: String): String = try {
        JSONObject(json).optString("message", "")
    } catch (e: Exception) {
        ""
    }

    fun voteToWire(vote: CredibilityVote): String = when (vote) {
        CredibilityVote.CREDIBLE -> "credible"
        CredibilityVote.NOT_CREDIBLE -> "not_credible"
    }

    private fun voteFromWire(value: String): CredibilityVote? = when (value) {
        "credible" -> CredibilityVote.CREDIBLE
        "not_credible" -> CredibilityVote.NOT_CREDIBLE
        else -> null
    }
}
