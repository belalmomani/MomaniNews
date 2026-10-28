package com.momaninews.app.ui.screens.store

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.momaninews.app.data.store.StoreApp
import com.momaninews.app.data.store.StoreDownloader
import com.momaninews.app.data.store.StoreLogic
import com.momaninews.app.data.store.StoreRepositories
import com.momaninews.app.ui.components.ErrorState
import com.momaninews.app.ui.components.LoadingState
import com.momaninews.app.ui.components.MessageState
import com.momaninews.app.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun StoreScreen(onBack: () -> Unit) {
    val repo = StoreRepositories.default
    var apps by remember { mutableStateOf<List<StoreApp>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<StoreApp?>(null) }

    LaunchedEffect(attempt) {
        apps = null
        failed = false
        if (repo == null) return@LaunchedEffect
        try {
            val r = withTimeoutOrNull(25_000L) { repo.list() }
            if (r == null) failed = true else apps = r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    ScreenScaffold(title = "متجري", onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val list = apps
            when {
                repo == null -> MessageState("غير متاح في الوضع التجريبي", "المتجر يحتاج اتصالاً بالخادم.")
                failed -> ErrorState("تعذر تحميل المتجر. تحقق من الاتصال.", onRetry = { attempt++ })
                list == null -> LoadingState()
                list.isEmpty() -> MessageState("المتجر فارغ حالياً", "ستظهر هنا التطبيقات بعد اعتمادها.")
                else -> {
                    val cats = StoreLogic.categories.filter { c -> list.any { it.category == c.wire } }
                    if (cats.size > 1) {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("الكل") }) }
                            items(cats, key = { it.wire }) { c ->
                                FilterChip(selected = category == c.wire, onClick = { category = c.wire }, label = { Text(c.label) })
                            }
                        }
                    }
                    val shown = StoreLogic.filter(list, category)
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.id }) { app -> AppCard(app, onOpen = { selected = app }) }
                    }
                }
            }
        }
    }

    selected?.let { app -> AppDialog(app, onDismiss = { selected = null }) }
}

@Composable
private fun AppIcon(url: String?, size: Int) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun AppCard(app: StoreApp, onOpen: () -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.iconUrl, 56)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(StoreLogic.categoryLabel(app.category), app.versionName, StoreLogic.formatSize(app.sizeBytes).ifEmpty { null }).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.description.isNotBlank()) Text(app.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (app.apkUrl != null) Button(onClick = { startDownload(context, app) }) { Text("تنزيل") }
        }
    }
}

private fun startDownload(context: android.content.Context, app: StoreApp) {
    val ok = StoreDownloader.start(context, app)
    Toast.makeText(context, if (ok) "بدأ التنزيل. عند اكتماله اضغط إشعار التنزيل لتثبيت التطبيق." else "رابط التنزيل غير صالح.", Toast.LENGTH_LONG).show()
}

@Composable
private fun AppDialog(app: StoreApp, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.iconUrl, 64)
                    Column(Modifier.weight(1f)) {
                        Text(app.title, style = MaterialTheme.typography.titleMedium)
                        val meta = listOfNotNull(StoreLogic.categoryLabel(app.category), app.versionName, StoreLogic.formatSize(app.sizeBytes).ifEmpty { null }).joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (app.screenshots.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(app.screenshots) { u ->
                            AsyncImage(
                                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.height(220.dp).width(124.dp).clip(RoundedCornerShape(10.dp))
                            )
                        }
                    }
                }
                if (app.description.isNotBlank()) Text(app.description, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "التطبيق من مستودع GitHub «${app.repo}» ويُنزَّل مباشرة من إصداراته. بعد التنزيل اسمح للنظام بالتثبيت من هذا المصدر عند طلبه.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (app.apkUrl != null) Button(onClick = { startDownload(context, app) }, modifier = Modifier.fillMaxWidth()) { Text("تنزيل مباشر") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("إغلاق") }
            }
        }
    }
}
