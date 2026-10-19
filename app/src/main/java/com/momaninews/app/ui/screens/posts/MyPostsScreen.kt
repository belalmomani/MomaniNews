package com.momaninews.app.ui.screens.posts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.posts.MyPost
import com.momaninews.app.data.posts.PostsRepositories
import com.momaninews.app.ui.components.MessageState
import com.momaninews.app.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException

/** حالة منشورات المستخدم: بانتظار الاعتماد / منشور / لم يُنشر. لا تُعرض أسباب الرفض الداخلية. */
@Composable
fun MyPostsScreen(onBack: () -> Unit, onSignInClick: () -> Unit) {
    val repo = PostsRepositories.default
    val user by AuthRepositories.default.currentUser.collectAsStateWithLifecycle()
    var posts by remember { mutableStateOf<List<MyPost>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(user?.id) {
        if (repo == null || user == null) return@LaunchedEffect
        try { posts = repo.mine(); failed = false } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true }
    }

    ScreenScaffold(title = "منشوراتي", onBack = onBack) { padding ->
        val list = posts
        when {
            repo == null -> MessageState("غير متاح في الوضع التجريبي", "يحتاج تسجيل الدخول بحساب Google الحقيقي.", Modifier.padding(padding))
            user == null -> MessageState("لم تسجّل الدخول", "سجّل الدخول لترى منشوراتك.", Modifier.padding(padding), "تسجيل الدخول", onSignInClick)
            failed -> MessageState("تعذر التحميل", "تحقق من الاتصال وحاول لاحقاً.", Modifier.padding(padding))
            list == null -> Text("جارٍ التحميل…", modifier = Modifier.padding(padding).padding(16.dp))
            list.isEmpty() -> MessageState("لا توجد منشورات", "شارك خبراً من منطقتك من الصفحة الرئيسية.", Modifier.padding(padding))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list) { p ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(
                                p.statusLabel + (if (p.dateLabel.isNotEmpty()) " • ${p.dateLabel}" else ""),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
