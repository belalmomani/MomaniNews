package com.momaninews.app.data.interaction

import com.momaninews.app.data.model.Comment
import com.momaninews.app.data.model.CredibilityVote
import com.momaninews.app.data.model.VoteSummary
import com.momaninews.app.data.remote.RestResponse
import com.momaninews.app.data.remote.SupabaseRest
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CommentRateLimitedException : Exception("comment rate limited")

/**
 * التصويت والتعليقات عبر Supabase. القواعد الفعلية (صوت واحد لكل مستخدم، الحظر، حد معدل التعليق،
 * الأخبار المعتمدة فقط) تفرضها القاعدة؛ هذا الكود لا يعتمد عليه الأمان.
 * [tokenProvider] يُرجع رمز وصول صالحاً أو null إن لم يسجَّل الدخول.
 */
class SupabaseInteractionRepository(
    private val rest: SupabaseRest,
    private val tokenProvider: suspend () -> String?
) : InteractionRepository {

    override suspend fun getVotes(newsId: Int): VoteSummary = withContext(Dispatchers.IO) {
        val counts = rest.call("GET", "news_vote_counts?select=credible,not_credible&news_id=eq.$newsId&limit=1")
        requireOk(counts)
        val (credible, notCredible) = SupabaseInteractionParser.parseCounts(counts.body)

        val token = tokenProvider()
        val mine = if (token == null) null else {
            val r = rest.call("GET", "news_votes?select=vote&news_id=eq.$newsId&limit=1", bearer = token)
            requireOk(r)
            SupabaseInteractionParser.parseMyVote(r.body)
        }
        VoteSummary(credible, notCredible, mine)
    }

    override suspend fun vote(newsId: Int, vote: CredibilityVote): VoteSummary {
        val token = tokenProvider() ?: throw NotSignedInException()
        withContext(Dispatchers.IO) {
            val wire = SupabaseInteractionParser.voteToWire(vote)
            // تعديل صوت موجود (RLS يحصره في صفوف المستخدم)، وإن لم يوجد يُنشأ. التعارض الفريد يُعاد معه التعديل.
            if (!patchVote(newsId, wire, token)) {
                val insert = rest.call(
                    "POST", "news_votes",
                    JSONObject().put("news_id", newsId).put("vote", wire).toString(),
                    bearer = token, prefer = "return=minimal"
                )
                if (insert.code == 409) {
                    if (!patchVote(newsId, wire, token)) throw IOException("vote conflict")
                } else {
                    requireOk(insert)
                }
            }
        }
        return getVotes(newsId)
    }

    override suspend fun getComments(newsId: Int): List<Comment> = withContext(Dispatchers.IO) {
        val r = rest.call(
            "GET",
            "comments_public?select=id,body,created_at,display_name&news_id=eq.$newsId&order=created_at.desc&limit=100"
        )
        requireOk(r)
        SupabaseInteractionParser.parseComments(r.body)
    }

    override suspend fun addComment(newsId: Int, text: String): Comment {
        val token = tokenProvider() ?: throw NotSignedInException()
        val clean = InteractionRules.cleanComment(text) ?: throw InvalidCommentException()
        withContext(Dispatchers.IO) {
            val r = rest.call(
                "POST", "comments",
                JSONObject().put("news_id", newsId).put("body", clean).toString(),
                bearer = token, prefer = "return=minimal"
            )
            if (r.code == 400 && SupabaseInteractionParser.errorMessage(r.body) == "comment_rate_limited") {
                throw CommentRateLimitedException()
            }
            requireOk(r)
        }
        // التعليق المنشور يُقرأ من العرض العام (المصدر الوحيد للعرض) فيظهر بالاسم والتاريخ الصحيحين.
        return getComments(newsId).firstOrNull() ?: throw IOException("comment not visible")
    }

    /** true إذا عُدّل صف واحد على الأقل. */
    private fun patchVote(newsId: Int, wire: String, token: String): Boolean {
        val r = rest.call(
            "PATCH", "news_votes?news_id=eq.$newsId",
            JSONObject().put("vote", wire).toString(),
            bearer = token, prefer = "return=representation"
        )
        requireOk(r)
        return JSONArray(r.body).length() > 0
    }

    private fun requireOk(r: RestResponse) {
        if (!r.ok) throw IOException("HTTP ${r.code}")
    }
}
