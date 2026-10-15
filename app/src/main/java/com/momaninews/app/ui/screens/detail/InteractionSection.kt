package com.momaninews.app.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.AuthRepository
import com.momaninews.app.data.interaction.InteractionRepositories
import com.momaninews.app.data.interaction.InteractionRepository
import com.momaninews.app.data.interaction.InteractionRules
import com.momaninews.app.data.model.Comment
import com.momaninews.app.data.model.CredibilityVote
import com.momaninews.app.data.model.VoteSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** التصويت على المصداقية والتعليقات أسفل الخبر. حالياً تجريبي محلي. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractionSection(
    newsId: Int,
    onSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
    auth: AuthRepository = AuthRepositories.default,
    interaction: InteractionRepository = InteractionRepositories.default
) {
    val user by auth.currentUser.collectAsStateWithLifecycle()
    var votes by remember(newsId) { mutableStateOf(VoteSummary(0, 0, null)) }
    var comments by remember(newsId) { mutableStateOf<List<Comment>>(emptyList()) }
    var draft by remember(newsId) { mutableStateOf("") }
    var message by remember(newsId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(newsId, user?.id) {
        try {
            votes = interaction.getVotes(newsId)
            comments = interaction.getComments(newsId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "تعذر تحميل التفاعل."
        }
    }

    fun onVote(vote: CredibilityVote) {
        if (!InteractionRules.canVote(user)) {
            onSignInClick()
            return
        }
        scope.launch {
            try {
                votes = interaction.vote(newsId, vote)
                message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "تعذر تسجيل التصويت."
            }
        }
    }

    fun onSubmitComment() {
        val text = draft
        scope.launch {
            try {
                interaction.addComment(newsId, text)
                comments = interaction.getComments(newsId)
                draft = ""
                message = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "تعذر نشر التعليق."
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "هل الخبر موثوق؟",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = votes.myVote == CredibilityVote.CREDIBLE,
                onClick = { onVote(CredibilityVote.CREDIBLE) },
                label = { Text(text = "موثوق (${votes.credible})") }
            )
            FilterChip(
                selected = votes.myVote == CredibilityVote.NOT_CREDIBLE,
                onClick = { onVote(CredibilityVote.NOT_CREDIBLE) },
                label = { Text(text = "غير موثوق (${votes.notCredible})") }
            )
        }

        Text(
            text = "التعليقات (${comments.size})",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (InteractionRules.canComment(user)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = "اكتب تعليقاً") },
                supportingText = {
                    Text(text = "${draft.trim().length} / ${InteractionRules.MAX_COMMENT_LENGTH}")
                },
                isError = draft.trim().length > InteractionRules.MAX_COMMENT_LENGTH,
                minLines = 2,
                maxLines = 5
            )
            Button(
                onClick = { onSubmitComment() },
                enabled = InteractionRules.cleanComment(draft) != null
            ) {
                Text(text = "نشر التعليق")
            }
        } else {
            OutlinedButton(onClick = onSignInClick) {
                Text(text = "سجّل الدخول للتعليق")
            }
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        comments.forEach { comment ->
            CommentCard(comment = comment)
        }
    }
}

@Composable
private fun CommentCard(comment: Comment) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = comment.authorName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = comment.dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // نص عادي فقط (لا HTML ولا روابط قابلة للنقر)، فلا يوجد خطر حقن في العرض.
            Text(
                text = comment.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
