package com.momaninews.app.data.interaction

import com.momaninews.app.BuildConfig
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.AuthRepository
import com.momaninews.app.data.auth.SupabaseAuthRepository
import com.momaninews.app.data.remote.SupabaseRest
import com.momaninews.app.data.model.Comment
import com.momaninews.app.data.model.CredibilityVote
import com.momaninews.app.data.model.User
import com.momaninews.app.data.model.VoteSummary

class NotSignedInException : Exception("not signed in")
class InvalidCommentException : Exception("invalid comment")

/**
 * قواعد التفاعل في مكان واحد. من يحق له التعليق والتصويت قرار مفتوح (انظر docs/SUPABASE_SETUP.md)؛
 * الافتراض الحالي: أي مستخدم مسجّل. الخادم هو الذي سيفرض القاعدة الفعلية.
 */
object InteractionRules {
    const val MAX_COMMENT_LENGTH = 500

    fun canVote(user: User?): Boolean = user != null
    fun canComment(user: User?): Boolean = user != null

    /** يُرجع النص بعد القص، أو null إذا كان فارغاً أو أطول من الحد. */
    fun cleanComment(text: String): String? {
        val trimmed = text.trim()
        return if (trimmed.isEmpty() || trimmed.length > MAX_COMMENT_LENGTH) null else trimmed
    }
}

interface InteractionRepository {
    suspend fun getVotes(newsId: Int): VoteSummary
    suspend fun vote(newsId: Int, vote: CredibilityVote): VoteSummary
    suspend fun getComments(newsId: Int): List<Comment>
    suspend fun addComment(newsId: Int, text: String): Comment

    /** إبلاغ عن تعليق مسيء. التنفيذ التجريبي لا يفعل شيئاً. */
    suspend fun reportComment(commentId: Int, reason: String? = null) {}
}

/**
 * تنفيذ تجريبي في الذاكرة. يحاكي قاعدة «صوت واحد لكل مستخدم لكل خبر» بمفتاح (خبر، مستخدم)،
 * لكنه لا يغني عن القيد الفريد على الخادم وقاعدة البيانات.
 */
class DemoInteractionRepository(private val auth: AuthRepository) : InteractionRepository {

    private val lock = Any()
    private val votes = mutableMapOf<Pair<Int, String>, CredibilityVote>()
    private val comments = mutableMapOf<Int, MutableList<Comment>>()
    private var nextCommentId = 1

    override suspend fun getVotes(newsId: Int): VoteSummary = synchronized(lock) { summary(newsId) }

    override suspend fun vote(newsId: Int, vote: CredibilityVote): VoteSummary {
        val user = auth.currentUser.value ?: throw NotSignedInException()
        return synchronized(lock) {
            votes[newsId to user.id] = vote
            summary(newsId)
        }
    }

    override suspend fun getComments(newsId: Int): List<Comment> = synchronized(lock) {
        comments[newsId].orEmpty().reversed()
    }

    override suspend fun addComment(newsId: Int, text: String): Comment {
        val user = auth.currentUser.value ?: throw NotSignedInException()
        val clean = InteractionRules.cleanComment(text) ?: throw InvalidCommentException()
        return synchronized(lock) {
            val comment = Comment(
                id = nextCommentId++,
                authorName = user.displayName,
                text = clean,
                dateLabel = "الآن (تجريبي)",
                isSample = true
            )
            comments.getOrPut(newsId) { mutableListOf() }.add(comment)
            comment
        }
    }

    private fun summary(newsId: Int): VoteSummary {
        val forNews = votes.filterKeys { it.first == newsId }
        val me = auth.currentUser.value?.id
        return VoteSummary(
            credible = forNews.count { it.value == CredibilityVote.CREDIBLE },
            notCredible = forNews.count { it.value == CredibilityVote.NOT_CREDIBLE },
            myVote = if (me == null) null else forNews[newsId to me]
        )
    }
}

/**
 * Supabase إن كان الدخول الحقيقي مفعّلاً (SupabaseAuthRepository)، وإلا التنفيذ التجريبي المحلي.
 * تُقرأ بعد Application.onCreate (حيث تُهيَّأ AuthRepositories).
 */
object InteractionRepositories {
    val default: InteractionRepository by lazy {
        val auth = AuthRepositories.default
        if (auth is SupabaseAuthRepository) {
            SupabaseInteractionRepository(
                rest = SupabaseRest(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY),
                tokenProvider = { auth.accessToken() }
            )
        } else {
            DemoInteractionRepository(auth)
        }
    }
}
