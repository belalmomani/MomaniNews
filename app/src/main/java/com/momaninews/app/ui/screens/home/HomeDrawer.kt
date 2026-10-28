package com.momaninews.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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

enum class DrawerAction { HOME, REGION, PROFILE, NOTIFICATIONS, SAVED, STORE, MY_POSTS, AD_REQUEST, VERIFICATION, SETTINGS, ADMIN, SIGN_IN, SIGN_OUT }

data class DrawerEntry(val action: DrawerAction, val label: String, val regionId: String? = null)

/** منطق نقي لمحتوى القائمة الجانبية بحسب حالة المستخدم (قابل للاختبار). */
object DrawerLogic {
    /** الأقسام: الرئيسية ثم المناطق ثم أدوات الحساب. عناصر الإدارة والتوثيق تظهر لمن يحق له فقط. */
    fun entries(user: User?): List<DrawerEntry> = buildList {
        add(DrawerEntry(DrawerAction.HOME, "الرئيسية"))
        Regions.all.forEach { add(DrawerEntry(DrawerAction.REGION, it.nameAr, it.id)) }
        add(DrawerEntry(DrawerAction.SAVED, "المحفوظات"))
        add(DrawerEntry(DrawerAction.STORE, "متجري"))
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

    /** تقسيم القائمة لأقسام العرض: اختصارات (الرئيسية والمناطق)، أدواتي، المزيد (قابل للطيّ)، وزر الدخول/الخروج. */
    data class Sections(
        val shortcuts: List<DrawerEntry>,
        val main: List<DrawerEntry>,
        val more: List<DrawerEntry>,
        val footer: DrawerEntry?
    )

    fun sections(user: User?): Sections {
        val all = entries(user)
        fun pick(vararg a: DrawerAction) = all.filter { it.action in a }
        return Sections(
            shortcuts = pick(DrawerAction.HOME, DrawerAction.REGION),
            main = pick(DrawerAction.SAVED, DrawerAction.STORE, DrawerAction.NOTIFICATIONS, DrawerAction.MY_POSTS, DrawerAction.AD_REQUEST),
            more = pick(DrawerAction.VERIFICATION, DrawerAction.SETTINGS, DrawerAction.ADMIN),
            footer = all.firstOrNull { it.action == DrawerAction.SIGN_IN || it.action == DrawerAction.SIGN_OUT }
        )
    }

    fun initial(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "م"
}

private fun iconFor(a: DrawerAction): ImageVector = when (a) {
    DrawerAction.SAVED -> Icons.Default.Star
    DrawerAction.STORE -> Icons.Default.ShoppingCart
    DrawerAction.NOTIFICATIONS -> Icons.Default.Notifications
    DrawerAction.MY_POSTS -> Icons.Default.Edit
    DrawerAction.AD_REQUEST -> Icons.Default.Info
    DrawerAction.VERIFICATION -> Icons.Default.CheckCircle
    DrawerAction.SETTINGS -> Icons.Default.Settings
    DrawerAction.ADMIN -> Icons.Default.Lock
    DrawerAction.SIGN_IN -> Icons.Default.Person
    DrawerAction.SIGN_OUT -> Icons.Default.Close
    else -> Icons.Default.Home
}

@Composable
fun HomeDrawerContent(
    user: User?,
    selectedRegionId: String?,
    onEntry: (DrawerEntry) -> Unit
) {
    val sec = DrawerLogic.sections(user)
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            ProfileCard(user) { onEntry(DrawerEntry(DrawerAction.PROFILE, "الحساب")) }

            SectionLabel("اختصاراتك")
            // بلاطات بعمودين: الرئيسية ثم المناطق
            sec.shortcuts.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { e ->
                        val selected = (e.action == DrawerAction.HOME && selectedRegionId == null) ||
                            (e.action == DrawerAction.REGION && e.regionId == selectedRegionId)
                        ShortcutTile(e, selected, Modifier.weight(1f)) { onEntry(e) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (sec.main.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                sec.main.forEach { e ->
                    NavigationDrawerItem(
                        icon = { Icon(iconFor(e.action), contentDescription = null) },
                        label = { Text(e.label) },
                        selected = false,
                        onClick = { onEntry(e) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            if (sec.more.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                // مجموعة قابلة للطيّ كـ«الإعدادات والخصوصية» في فيسبوك
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { moreOpen = !moreOpen }.padding(horizontal = 28.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("الإعدادات والمزيد", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(if (moreOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (moreOpen) "طيّ" else "توسيع")
                }
                if (moreOpen) sec.more.forEach { e ->
                    NavigationDrawerItem(
                        icon = { Icon(iconFor(e.action), contentDescription = null) },
                        label = { Text(e.label) },
                        selected = false,
                        onClick = { onEntry(e) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            sec.footer?.let { e ->
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                NavigationDrawerItem(
                    icon = { Icon(iconFor(e.action), contentDescription = null) },
                    label = { Text(e.label, color = if (e.action == DrawerAction.SIGN_OUT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                    selected = false,
                    onClick = { onEntry(e) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

/** بطاقة الحساب أعلى القائمة: تفتح صفحة الحساب (أو تسجيل الدخول للزائر). */
@Composable
private fun ProfileCard(user: User?, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
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
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    user?.email ?: "سجّل الدخول للتعليق والتصويت",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ShortcutTile(e: DrawerEntry, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier.clip(shape).clickable(onClick = onClick)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), shape),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(30.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (e.action == DrawerAction.HOME) {
                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Text(DrawerLogic.initial(e.label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(e.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 6.dp)
    )
}
