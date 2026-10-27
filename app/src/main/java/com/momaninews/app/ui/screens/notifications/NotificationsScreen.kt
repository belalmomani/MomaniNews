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
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(user?.id, attempt) {
        list = null
        error = null
        if (repo == null || user == null) return@LaunchedEffect
        try {
            // مهلة صريحة: لا نبقى على «جارٍ التحميل» إلى الأبد
            val result = kotlinx.coroutines.withTimeoutOrNull(25_000L) { repo.list() }
            if (result == null) error = "انتهت المهلة (25 ثانية) دون رد."
            else list = result
            // لا نعلّم شيئاً مقروءاً تلقائياً: المستخدم يقرّر (فتح الإشعار أو الأزرار)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = (e::class.java.simpleName + ": " + (e.message ?: "")).take(160)
        }
    }

    ScreenScaffold(title = "الإشعارات", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                repo == null -> MessageState("غير متاح في الوضع التجريبي", "الإشعارات تحتاج تسجيل الدخول بحساب Google الحقيقي.")
                user == null -> MessageState("لم تسجّل الدخول", "سجّل الدخول لتصلك الإشعارات.", actionLabel = "تسجيل الدخول", onAction = onSignInClick)
                error != null -> MessageState("تعذر تحميل الإشعارات", "السبب: $error", actionLabel = "إعادة المحاولة", onAction = { attempt++ })
                list == null -> Text("جارٍ التحميل…", modifier = Modifier.padding(16.dp))
                list!!.isEmpty() -> MessageState("لا توجد إشعارات", "ستظهر هنا إشعارات الأخبار والتوثيق.")
                else -> {
                    val readIds by com.momaninews.app.data.notifications.NotifReadStore.read.collectAsStateWithLifecycle()
                    val unreadIds by com.momaninews.app.data.notifications.NotifReadStore.unread.collectAsStateWithLifecycle()
                    val now = remember(list) { System.currentTimeMillis() }
                    val states = list!!.associate { it.id to com.momaninews.app.data.notifications.NotifReadLogic.isUnread(it, readIds.toSet(), unreadIds.toSet(), now) }
                    val unreadCount = states.values.count { it }
                    Column(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                if (unreadCount > 0) "$unreadCount غير مقروء" else "كل الإشعارات مقروءة",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (unreadCount > 0) androidx.compose.material3.TextButton(onClick = {
                                com.momaninews.app.data.notifications.NotifReadStore.markAllRead(list!!.map { it.id })
                            }) { Text("تعليم الكل كمقروء") }
                        }
                        LazyColumn(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(list!!, key = { it.id }) { n ->
                                NotificationCard(
                                    n = n,
                                    unread = states[n.id] == true,
                                    onOpen = { id ->
                                        com.momaninews.app.data.notifications.NotifReadStore.markRead(n.id)
                                        onNewsClick(id)
                                    },
                                    onToggle = {
                                        if (states[n.id] == true) com.momaninews.app.data.notifications.NotifReadStore.markRead(n.id)
                                        else com.momaninews.app.data.notifications.NotifReadStore.markUnread(n.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(n: AppNotification, unread: Boolean, onOpen: (Int) -> Unit, onToggle: () -> Unit) {
    val target = n.newsId
    Surface(
        modifier = Modifier.fillMaxWidth().let { if (target != null) it.clickable { onOpen(target) } else it.clickable { if (unread) onToggle() } },
        shape = RoundedCornerShape(12.dp),
        color = if (unread) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (unread) 2.dp else 1.dp, if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = (if (unread) "● " else "") + n.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (n.body.isNotEmpty()) {
                Text(n.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(n.dateLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onToggle) { Text(if (unread) "تعليم كمقروء" else "تعليم كغير مقروء") }
            }
        }
    }
}
