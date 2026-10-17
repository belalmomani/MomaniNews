package com.momaninews.app.ui.screens.notifications

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.notifications.AppNotification
import com.momaninews.app.data.notifications.NotificationsRepositories
import com.momaninews.app.ui.components.MessageState
import com.momaninews.app.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException

@Composable
fun NotificationsScreen(onBack: () -> Unit, onNewsClick: (Int) -> Unit, onSignInClick: () -> Unit) {
    val repo = NotificationsRepositories.default
    val user by AuthRepositories.default.currentUser.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<List<AppNotification>?>(null) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(user?.id) {
        list = null
        error = false
        if (repo == null || user == null) return@LaunchedEffect
        try {
            list = repo.list()
            repo.markPersonalRead() // بعد العرض: تبقى علامة «جديد» ظاهرة في هذه الزيارة فقط
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = true
        }
    }

    ScreenScaffold(title = "الإشعارات", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                repo == null -> MessageState("غير متاح في الوضع التجريبي", "الإشعارات تحتاج تسجيل الدخول بحساب Google الحقيقي.")
                user == null -> MessageState("لم تسجّل الدخول", "سجّل الدخول لتصلك الإشعارات.", actionLabel = "تسجيل الدخول", onAction = onSignInClick)
                error -> MessageState("تعذر تحميل الإشعارات", "تحقق من الاتصال وحاول لاحقاً.")
                list == null -> Text("جارٍ التحميل…", modifier = Modifier.padding(16.dp))
                list!!.isEmpty() -> MessageState("لا توجد إشعارات", "ستظهر هنا إشعارات الأخبار والتوثيق.")
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list!!, key = { it.id }) { n -> NotificationCard(n, onNewsClick) }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(n: AppNotification, onNewsClick: (Int) -> Unit) {
    val target = n.newsId
    Surface(
        modifier = Modifier.fillMaxWidth().let { if (target != null) it.clickable { onNewsClick(target) } else it },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (n.isUnread) 2.dp else 1.dp, if (n.isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = (if (n.isUnread) "جديد • " else "") + n.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (n.isUnread) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (n.body.isNotEmpty()) {
                Text(n.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            if (n.dateLabel.isNotEmpty()) {
                Text(n.dateLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
