package com.momaninews.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.BuildConfig
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.settings.SettingsStore
import com.momaninews.app.data.settings.TextScales
import com.momaninews.app.data.settings.ThemeMode
import com.momaninews.app.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "حسب النظام"
    ThemeMode.LIGHT -> "فاتح"
    ThemeMode.DARK -> "داكن"
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    val auth = AuthRepositories.default
    val user by auth.currentUser.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(title = "الإعدادات", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("المظهر", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    OptionRow(label = themeLabel(mode), selected = settings.theme == mode) { SettingsStore.setTheme(mode) }
                }
            }
            HorizontalDivider()
            Text("حجم الخط", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Column(Modifier.selectableGroup()) {
                TextScales.options.forEach { scale ->
                    OptionRow(label = TextScales.label(scale), selected = settings.textScale == scale) { SettingsStore.setTextScale(scale) }
                }
            }
            HorizontalDivider()
            if (BuildConfig.PRIVACY_URL.startsWith("https://")) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("سياسة الخصوصية") }
            }
            Text(
                "الإصدار ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (user != null) {
                HorizontalDivider()
                Text(
                    "حذف الحساب يزيل حسابك وتعليقاتك وتصويتك وطلبات التوثيق والإشعارات نهائياً ولا يمكن التراجع عنه.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { deleteError = null; confirmDelete = true },
                    enabled = !deleting,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("حذف حسابي", color = MaterialTheme.colorScheme.error) }
                deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("حذف الحساب نهائياً؟") },
            text = { Text("سيُحذف حسابك وكل بياناتك ولا يمكن استرجاعها.") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            try {
                                auth.deleteAccount()
                                confirmDelete = false
                            } catch (e: Exception) {
                                confirmDelete = false
                                deleteError = "تعذّر حذف الحساب. تحقق من الاتصال وحاول مرة أخرى."
                            } finally {
                                deleting = false
                            }
                        }
                    }
                ) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
