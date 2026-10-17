package com.momaninews.app.ui.screens.ads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.momaninews.app.data.ads.AdDraft
import com.momaninews.app.data.ads.AdRequestException
import com.momaninews.app.data.ads.AdRules
import com.momaninews.app.data.ads.AdsRepositories
import com.momaninews.app.data.ads.MyAd
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.model.Regions
import com.momaninews.app.ui.components.MessageState
import com.momaninews.app.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdRequestScreen(onBack: () -> Unit, onSignInClick: () -> Unit) {
    val repo = AdsRepositories.default
    val user by AuthRepositories.default.currentUser.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var region by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf<List<MyAd>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(user?.id, reload) {
        if (repo == null || user == null) return@LaunchedEffect
        try { mine = repo.mine() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* القائمة اختيارية */ }
    }

    val draft = AdDraft(title, body, contact, link, region)
    val valid = AdRules.clean(draft) != null

    ScreenScaffold(title = "أعلن معنا", onBack = onBack) { padding ->
        when {
            repo == null -> MessageState("غير متاح في الوضع التجريبي", "طلب الإعلان يحتاج تسجيل الدخول بحساب Google الحقيقي.", Modifier.padding(padding))
            user == null -> MessageState("لم تسجّل الدخول", "سجّل الدخول لإرسال طلب إعلان.", Modifier.padding(padding), "تسجيل الدخول", onSignInClick)
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "يُراجع المدير كل طلب قبل نشره، ويحدد مدة العرض. لا يُنشر شيء قبل الاعتماد.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(title, { title = it.take(AdRules.MAX_TITLE) }, Modifier.fillMaxWidth(), label = { Text("عنوان الإعلان") }, singleLine = true)
                OutlinedTextField(body, { body = it.take(AdRules.MAX_BODY) }, Modifier.fillMaxWidth(), label = { Text("نص الإعلان (اختياري)") }, minLines = 3, maxLines = 6)
                OutlinedTextField(contact, { contact = it.take(AdRules.MAX_CONTACT) }, Modifier.fillMaxWidth(), label = { Text("وسيلة التواصل (هاتف أو غيره)") }, singleLine = true)
                OutlinedTextField(
                    link, { link = it }, Modifier.fillMaxWidth(), label = { Text("رابط (اختياري، يبدأ بـ https://)") }, singleLine = true,
                    isError = link.isNotBlank() && !link.trim().startsWith("https://")
                )
                Text("المنطقة (اختياري)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Regions.all.forEach { r ->
                        FilterChip(selected = region == r.id, onClick = { region = if (region == r.id) null else r.id }, label = { Text(r.nameAr) })
                    }
                }
                Button(
                    enabled = valid && !busy,
                    onClick = {
                        busy = true; message = null
                        scope.launch {
                            try {
                                repo.submit(draft)
                                title = ""; body = ""; contact = ""; link = ""; region = null
                                message = "أُرسل الطلب وسيُراجع قريباً."; isError = false
                                reload++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: AdRequestException) {
                                isError = true
                                message = when (e.reason) {
                                    AdRequestException.Reason.TOO_MANY_PENDING -> "لديك 5 طلبات قيد المراجعة. انتظر مراجعتها أولاً."
                                    AdRequestException.Reason.INVALID -> "تحقق من الحقول (العنوان مطلوب، والرابط https)."
                                    else -> "تعذر إرسال الطلب. حاول مرة أخرى."
                                }
                            } catch (e: Exception) {
                                isError = true; message = "تعذر إرسال الطلب. تحقق من الاتصال."
                            }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "جارٍ الإرسال…" else "إرسال الطلب") }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                if (mine.isNotEmpty()) {
                    Text("طلباتي", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    mine.forEach { ad ->
                        Text(
                            "${ad.title} — ${ad.statusLabel}" + (if (ad.note.isNotEmpty()) " (${ad.note})" else "") + (if (ad.dateLabel.isNotEmpty()) " • ${ad.dateLabel}" else ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
