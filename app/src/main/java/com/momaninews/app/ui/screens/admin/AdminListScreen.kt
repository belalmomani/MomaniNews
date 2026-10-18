package com.momaninews.app.ui.screens.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.momaninews.app.data.admin.AdminItem
import com.momaninews.app.ui.components.MessageState
import com.momaninews.app.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** إجراء على عنصر. [confirm] يطلب تأكيداً، و[prompt] يطلب إدخال نص (يُمرَّر إلى [run]). */
class AdminAction(
    val label: String,
    val danger: Boolean = false,
    val confirm: String? = null,
    val prompt: String? = null,
    val promptDefault: String = "",
    val run: suspend (item: AdminItem, input: String?) -> Unit
)

class AdminTab(
    val label: String,
    val emptyText: String,
    val load: suspend () -> List<AdminItem>,
    val actionsFor: (AdminItem) -> List<AdminAction>
)

private class PendingAction(val item: AdminItem, val action: AdminAction)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminListScreen(title: String, onBack: () -> Unit, tabs: List<AdminTab>) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tab = tabs[tabIndex.coerceIn(0, tabs.lastIndex)]
    var list by remember { mutableStateOf<List<AdminItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tabIndex, reload) {
        list = null
        failed = false
        try {
            list = tab.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    fun execute(p: PendingAction, text: String?) {
        busy = true
        scope.launch {
            try {
                p.action.run(p.item, text)
                message = "تم."
                reload++
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                message = "قيمة غير صالحة."
            } catch (e: Exception) {
                message = "تعذر التنفيذ: " + (e.message ?: "خطأ غير معروف")
            } finally {
                busy = false
            }
        }
    }

    ScreenScaffold(title = title, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tabs.size > 1) {
                ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 8.dp) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = i == tabIndex, onClick = { tabIndex = i; message = null }, text = { Text(t.label, maxLines = 1, softWrap = false) })
                    }
                }
            }
            message?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
            }
            val shown = list // نسخة ثابتة: القائمة الأصلية تصير null أثناء إعادة التحميل ويُقرأ المحتوى لاحقاً
            when {
                failed -> MessageState("تعذر التحميل", "تحقق من الاتصال، ومن أن حسابك مدير.")
                shown == null -> Text("جارٍ التحميل…", modifier = Modifier.padding(16.dp))
                shown.isEmpty() -> MessageState(tab.emptyText, "")
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(shown, key = { it.ref }) { item ->
                        AdminCard(item, tab.actionsFor(item), busy) { action ->
                            val p = PendingAction(item, action)
                            if (action.confirm != null || action.prompt != null) {
                                input = action.promptDefault
                                pending = p
                            } else execute(p, null)
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(p.action.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.action.confirm?.let { Text(it) }
                    p.action.prompt?.let {
                        OutlinedTextField(value = input, onValueChange = { v -> input = v.take(500) }, label = { Text(it) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    execute(p, if (p.action.prompt != null) input else null)
                }) { Text("موافق") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("إلغاء") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminCard(item: AdminItem, actions: List<AdminAction>, busy: Boolean, onAction: (AdminAction) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            if (item.meta.isNotBlank()) Text(item.meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.body.isNotBlank()) Text(item.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { a ->
                    OutlinedButton(onClick = { onAction(a) }, enabled = !busy) {
                        Text(a.label, color = if (a.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
