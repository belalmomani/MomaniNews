package com.momaninews.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.momaninews.app.data.model.Regions
import com.momaninews.app.data.model.User
import com.momaninews.app.data.model.VerificationStatus

enum class DrawerAction { HOME, REGION, NOTIFICATIONS, MY_POSTS, AD_REQUEST, VERIFICATION, SETTINGS, ADMIN, SIGN_IN, SIGN_OUT }

data class DrawerEntry(val action: DrawerAction, val label: String, val regionId: String? = null)

/** منطق نقي لمحتوى القائمة الجانبية بحسب حالة المستخدم (قابل للاختبار). */
object DrawerLogic {
    /** الأقسام: الرئيسية ثم المناطق ثم أدوات الحساب. عناصر الإدارة والتوثيق تظهر لمن يحق له فقط. */
    fun entries(user: User?): List<DrawerEntry> = buildList {
        add(DrawerEntry(DrawerAction.HOME, "الرئيسية"))
        Regions.all.forEach { add(DrawerEntry(DrawerAction.REGION, it.nameAr, it.id)) }
        if (user != null) {
            add(DrawerEntry(DrawerAction.NOTIFICATIONS, "الإشعارات"))
            add(DrawerEntry(DrawerAction.MY_POSTS, "منشوراتي"))
            add(DrawerEntry(DrawerAction.AD_REQUEST, "أعلن معنا"))
            if (!user.isDemo && user.verification != VerificationStatus.APPROVED) {
                add(DrawerEntry(DrawerAction.VERIFICATION, if (user.verification == VerificationStatus.NONE) "طلب التوثيق" else "حالة طلب التوثيق"))
            }
        }
        add(DrawerEntry(DrawerAction.SETTINGS, "الإعدادات"))
        if (user?.isAdmin == true) add(DrawerEntry(DrawerAction.ADMIN, "لوحة الإدارة"))
        add(if (user == null) DrawerEntry(DrawerAction.SIGN_IN, "تسجيل الدخول") else DrawerEntry(DrawerAction.SIGN_OUT, "تسجيل الخروج"))
    }

    fun initial(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "م"
}

@Composable
fun HomeDrawerContent(
    user: User?,
    selectedRegionId: String?,
    onEntry: (DrawerEntry) -> Unit
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // رأس الحساب (كصفحة الملف في فيسبوك)
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        DrawerLogic.initial(user?.displayName ?: ""),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        user?.displayName ?: "زائر",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1
                    )
                    Text(
                        user?.email ?: "سجّل الدخول للتعليق والتصويت",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1
                    )
                }
            }

            val entries = DrawerLogic.entries(user)
            entries.forEachIndexed { i, e ->
                // فواصل بين الأقسام: بعد الرئيسية، وبعد المناطق
                if (e.action == DrawerAction.REGION && entries[i - 1].action == DrawerAction.HOME) {
                    SectionLabel("المناطق")
                }
                if (e.action != DrawerAction.REGION && e.action != DrawerAction.HOME && entries[i - 1].action == DrawerAction.REGION) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                NavigationDrawerItem(
                    label = { Text(e.label) },
                    selected = (e.action == DrawerAction.HOME && selectedRegionId == null) ||
                        (e.action == DrawerAction.REGION && e.regionId == selectedRegionId),
                    onClick = { onEntry(e) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            Box(modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 4.dp)
    )
}
