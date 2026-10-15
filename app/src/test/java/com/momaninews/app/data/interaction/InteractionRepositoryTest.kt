package com.momaninews.app.data.interaction

import com.momaninews.app.data.auth.AuthRepository
import com.momaninews.app.data.model.CredibilityVote
import com.momaninews.app.data.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionRepositoryTest {

    private class FakeAuth(initial: User? = null) : AuthRepository {
        val state = MutableStateFlow(initial)
        override val currentUser: StateFlow<User?> = state
        override suspend fun signIn(): User = throw UnsupportedOperationException()
        override fun signOut() {
            state.value = null
        }
    }

    private fun user(id: String) = User(id = id, displayName = "مستخدم $id", email = "$id@example.invalid")

    // ---- InteractionRules
    @Test
    fun cleanComment_trimsAndRejectsEmpty() {
        assertEquals("نص", InteractionRules.cleanComment("  نص  "))
        assertNull(InteractionRules.cleanComment(""))
        assertNull(InteractionRules.cleanComment("   "))
    }

    @Test
    fun cleanComment_enforcesMaxLength() {
        val max = "ب".repeat(InteractionRules.MAX_COMMENT_LENGTH)
        assertEquals(max, InteractionRules.cleanComment(max))
        assertNull(InteractionRules.cleanComment(max + "ب"))
    }

    @Test
    fun guestCannotVoteOrComment() {
        assertTrue(!InteractionRules.canVote(null))
        assertTrue(!InteractionRules.canComment(null))
        assertTrue(InteractionRules.canVote(user("a")))
    }

    // ---- votes
    @Test
    fun vote_requiresSignIn() {
        val repo = DemoInteractionRepository(FakeAuth(null))
        assertThrows(NotSignedInException::class.java) {
            runBlocking { repo.vote(1, CredibilityVote.CREDIBLE) }
        }
    }

    @Test
    fun vote_isOnePerUserPerNews_changeReplaces() = runBlocking {
        val repo = DemoInteractionRepository(FakeAuth(user("a")))
        repo.vote(1, CredibilityVote.CREDIBLE)
        repo.vote(1, CredibilityVote.CREDIBLE) // تكرار نفس الصوت لا يزيد العدّاد
        var summary = repo.getVotes(1)
        assertEquals(1, summary.credible)
        assertEquals(0, summary.notCredible)

        summary = repo.vote(1, CredibilityVote.NOT_CREDIBLE) // تغيير الصوت يستبدل القديم
        assertEquals(0, summary.credible)
        assertEquals(1, summary.notCredible)
        assertEquals(CredibilityVote.NOT_CREDIBLE, summary.myVote)
    }

    @Test
    fun votes_areSeparatePerNewsAndPerUser() = runBlocking {
        val auth = FakeAuth(user("a"))
        val repo = DemoInteractionRepository(auth)
        repo.vote(1, CredibilityVote.CREDIBLE)
        repo.vote(2, CredibilityVote.NOT_CREDIBLE)

        auth.state.value = user("b")
        repo.vote(1, CredibilityVote.CREDIBLE)

        assertEquals(2, repo.getVotes(1).credible)
        assertEquals(1, repo.getVotes(2).notCredible)
        assertEquals(CredibilityVote.CREDIBLE, repo.getVotes(1).myVote)
        assertNull(repo.getVotes(2).myVote) // المستخدم b لم يصوّت على الخبر 2
    }

    @Test
    fun guestSeesCountsButNoOwnVote() = runBlocking {
        val auth = FakeAuth(user("a"))
        val repo = DemoInteractionRepository(auth)
        repo.vote(1, CredibilityVote.CREDIBLE)
        auth.signOut()
        val summary = repo.getVotes(1)
        assertEquals(1, summary.credible)
        assertNull(summary.myVote)
    }

    // ---- comments
    @Test
    fun addComment_requiresSignIn() {
        val repo = DemoInteractionRepository(FakeAuth(null))
        assertThrows(NotSignedInException::class.java) {
            runBlocking { repo.addComment(1, "نص") }
        }
    }

    @Test
    fun addComment_rejectsInvalidText() {
        val repo = DemoInteractionRepository(FakeAuth(user("a")))
        assertThrows(InvalidCommentException::class.java) {
            runBlocking { repo.addComment(1, "   ") }
        }
        assertThrows(InvalidCommentException::class.java) {
            runBlocking { repo.addComment(1, "ب".repeat(InteractionRules.MAX_COMMENT_LENGTH + 1)) }
        }
    }

    @Test
    fun comments_areListedNewestFirstPerNews() = runBlocking {
        val repo = DemoInteractionRepository(FakeAuth(user("a")))
        val first = repo.addComment(1, "أول")
        val second = repo.addComment(1, "ثان")
        repo.addComment(2, "خبر آخر")

        val list = repo.getComments(1)
        assertEquals(listOf(second.id, first.id), list.map { it.id })
        assertEquals(1, repo.getComments(2).size)
        assertNotNull(list.first().authorName)
    }
}
