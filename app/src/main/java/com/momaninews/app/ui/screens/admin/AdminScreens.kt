package com.momaninews.app.ui.screens.admin

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.data.admin.ADMIN_REGIONS
import com.momaninews.app.data.admin.AdminRepositories
import com.momaninews.app.data.admin.AdminRepository
import com.momaninews.app.data.admin.AdminRules
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.ui.components.MessageState
import com.momaninews.app.ui.components.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** يحمي كل شاشات الإدارة: يظهر المحتوى فقط للمدير في الوضع الحقيقي (والقاعدة تفرض الصلاحية فعلياً). */
@Composable
fun AdminGate(title: String, onBack: () -> Unit, content: @Composable (AdminRepository) -> Unit) {
    val user by AuthRepositories.default.currentUser.collectAsStateWithLifecycle()
    val repo = AdminRepositories.default
    if (repo == null || user?.isAdmin != true) {
        ScreenScaffold(title = title, onBack = onBack) { p ->
            Column(Modifier.fillMaxSize().padding(p)) { MessageState("غير مصرّح", "هذا القسم للمدراء فقط.") }
        }
    } else content(repo)
}

@Composable
fun AdminHomeScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    AdminGate("الإدارة", onBack) {
        ScreenScaffold(title = "الإدارة", onBack = onBack) { p ->
            Column(
                Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "الأخبار (اعتماد / رفض)" to "admin/news",
                    "إضافة خبر" to "admin/addnews",
                    "التعليقات" to "admin/comments",
                    "طلبات التوثيق" to "admin/verification",
                    "الإعلانات" to "admin/ads",
                    "المستخدمون" to "admin/users",
                    "إرسال إشعار عام" to "admin/notify"
                ).forEach { (label, route) ->
                    Button(onClick = { onOpen(route) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        }
    }
}

@Composable
fun AdminNewsScreen(onBack: () -> Unit) = AdminGate("الأخبار", onBack) { r ->
    AdminListScreen(
        "الأخبار", onBack, listOf(
            AdminTab("بانتظار الاعتماد", "لا توجد أخبار معلّقة", { r.pendingNews() }) { n -> listOf(
                AdminAction("اعتماد") { i, _ -> r.setNewsStatus(i.ref, "approved") },
                AdminAction("تعديل العنوان", prompt = "العنوان المعروض", promptDefault = n.title) { i, text -> r.editNewsText(i.ref, text, null) },
                AdminAction("تعديل الملخص", prompt = "الملخص المعروض", promptDefault = n.extra) { i, text -> r.editNewsText(i.ref, null, text) },
                AdminAction("رفض مع سبب", danger = true, prompt = "سبب الرفض (للمشرفين فقط)") { i, text -> r.rejectNews(i.ref, text ?: "") }
            ) },
            AdminTab("عمليات الجلب", "لا توجد عمليات جلب بعد", { r.fetchRuns() }) { emptyList() },
            AdminTab("الكلمات المفتاحية", "لا توجد كلمات", { listOf(AdminItem(ref = "add", title = "＋ إضافة كلمة", meta = "الصيغة: النوع الكلمة [المنطقة] [ctx] — مثال: region عبين abeen")) + r.keywords() }) { k ->
                if (k.ref == "add") listOf(AdminAction("إضافة", prompt = "النوع الكلمة [abeen/abelin/sakhra/momani] [ctx]") { _, text -> r.addKeyword(text ?: "") })
                else listOf(
                    AdminAction(if (k.flag) "تعطيل" else "تفعيل") { i, _ -> r.setKeywordActive(i.ref, !i.flag) },
                    AdminAction("حذف", danger = true, confirm = "حذف الكلمة؟") { i, _ -> r.deleteKeyword(i.ref) }
                )
            },
            AdminTab("حالة المصادر", "لا توجد مصادر", { r.sourceStatus() }) { emptyList() },
            AdminTab("الأخرى", "لا توجد أخبار", { r.allNews() }) { listOf(
                AdminAction("أرشفة", confirm = "أرشفة الخبر (يختفي من التطبيق)؟") { i, _ -> r.setNewsStatus(i.ref, "archived") },
                AdminAction("اعتماد") { i, _ -> r.setNewsStatus(i.ref, "approved") },
                AdminAction("حذف", danger = true, confirm = "حذف الخبر نهائياً؟") { i, _ -> r.deleteNews(i.ref) }
            ) }
        )
    )
}

@Composable
fun AdminCommentsScreen(onBack: () -> Unit) = AdminGate("التعليقات", onBack) { r ->
    AdminListScreen(
        "التعليقات", onBack, listOf(
            AdminTab("آخر التعليقات", "لا توجد تعليقات", { r.comments() }) { c -> listOf(
                AdminAction(if (c.flag) "إظهار" else "إخفاء") { i, _ -> r.setCommentHidden(i.ref, !i.flag) },
                AdminAction("حذف", danger = true, confirm = "حذف التعليق نهائياً؟") { i, _ -> r.deleteComment(i.ref) }
            ) }
        )
    )
}

@Composable
fun AdminVerificationScreen(onBack: () -> Unit) = AdminGate("طلبات التوثيق", onBack) { r ->
    val context = LocalContext.current
    AdminListScreen(
        "طلبات التوثيق", onBack, listOf(
            AdminTab("بانتظار المراجعة", "لا توجد طلبات", { r.pendingVerifications() }) { listOf(
                AdminAction("عرض المستند") { i, _ ->
                    val url = r.documentUrl(i.extra)
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                AdminAction("قبول", confirm = "قبول التوثيق؟") { i, _ -> r.setVerification(i.ref, true) },
                AdminAction("رفض", danger = true, confirm = "رفض التوثيق؟") { i, _ -> r.setVerification(i.ref, false) }
            ) }
        )
    )
}

@Composable
fun AdminAdsScreen(onBack: () -> Unit) = AdminGate("الإعلانات", onBack) { r ->
    AdminListScreen(
        "الإعلانات", onBack, listOf(
            AdminTab("طلبات", "لا توجد طلبات إعلان", { r.pendingAds() }) { listOf(
                AdminAction("اعتماد", prompt = "المدة بالأيام (1 - 365)", promptDefault = "7") { i, t ->
                    r.approveAd(i.ref, requireNotNull(AdminRules.parseDays(t)))
                },
                AdminAction("رفض", danger = true, prompt = "سبب الرفض (اختياري)") { i, t -> r.rejectAd(i.ref, t.orEmpty()) }
            ) },
            AdminTab("المعتمدة", "لا توجد إعلانات معتمدة", { r.approvedAds() }) { listOf(
                AdminAction("تمديد", prompt = "عدد أيام التمديد (1 - 365)", promptDefault = "7") { i, t ->
                    r.extendAd(i, requireNotNull(AdminRules.parseDays(t)))
                },
                AdminAction("إيقاف", danger = true, confirm = "إيقاف هذا الإعلان؟") { i, _ -> r.rejectAd(i.ref, "أوقفه المدير") },
                AdminAction("حذف", danger = true, confirm = "حذف الإعلان نهائياً؟") { i, _ -> r.deleteAd(i.ref) }
            ) }
        )
    )
}

@Composable
fun AdminUsersScreen(onBack: () -> Unit) = AdminGate("المستخدمون", onBack) { r ->
    AdminListScreen(
        "المستخدمون", onBack, listOf(
            AdminTab("آخر المستخدمين", "لا يوجد مستخدمون", { r.users() }) { u -> listOf(
                AdminAction(if (u.flag) "رفع الحظر" else "حظر", danger = !u.flag, confirm = "تأكيد الإجراء على هذا المستخدم؟") { i, _ ->
                    r.setUserFlags(i.ref, i.extra, !i.flag)
                },
                AdminAction(if (u.extra == "admin") "إزالة صلاحية المدير" else "جعله مديراً", confirm = "تغيير صلاحية هذا المستخدم؟") { i, _ ->
                    r.setUserFlags(i.ref, if (i.extra == "admin") "user" else "admin", i.flag)
                }
            ) }
        )
    )
}

@Composable
fun AdminAddNewsScreen(onBack: () -> Unit) = AdminGate("إضافة خبر", onBack) { r ->
    var title by remember { mutableStateOf("") }
    var excerpt by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(ADMIN_REGIONS.first().first) }
    var publish by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) imageUri = it }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) videoUri = it }

    ScreenScaffold(title = "إضافة خبر", onBack = onBack) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(title, { title = it.take(300) }, Modifier.fillMaxWidth(), label = { Text("العنوان") })
            OutlinedTextField(excerpt, { excerpt = it.take(600) }, Modifier.fillMaxWidth(), label = { Text("مقتطف (اختياري)") })
            OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth(), minLines = 6, label = { Text("النص الكامل (فقرات مفصولة بسطر فارغ)") })
            OutlinedTextField(source, { source = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("المصدر (اختياري)") })
            Text("المنطقة", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ADMIN_REGIONS.forEach { (id, name) ->
                    FilterChip(selected = region == id, onClick = { region = id }, label = { Text(name) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = !busy) { Text(if (imageUri == null) "إضافة صورة" else "تغيير الصورة") }
                if (imageUri != null) OutlinedButton(onClick = { imageUri = null }, enabled = !busy) { Text("إزالة") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickVideo.launch("video/*") }, enabled = !busy) { Text(if (videoUri == null) "إضافة فيديو (MP4، حتى 50MB)" else "تغيير الفيديو") }
                if (videoUri != null) OutlinedButton(onClick = { videoUri = null }, enabled = !busy) { Text("إزالة") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = publish, onCheckedChange = { publish = it })
                Text("نشر فوراً (وإلا يُحفظ بانتظار الاعتماد)", color = MaterialTheme.colorScheme.onBackground)
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(
                enabled = !busy && title.isNotBlank() && content.isNotBlank(),
                onClick = {
                    busy = true; message = null
                    scope.launch {
                        try {
                            message = if (imageUri != null || videoUri != null) "جارٍ رفع الملفات…" else null
                            val img = imageUri?.let { r.uploadImage(context, it) }
                            val vid = videoUri?.let { r.uploadVideo(context, it) }
                            r.addNews(title, excerpt, content, source, region, publish, img, vid)
                            title = ""; excerpt = ""; content = ""; source = ""; publish = false
                            imageUri = null; videoUri = null
                            message = "تم الحفظ."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IllegalArgumentException) {
                            message = "الملف غير مقبول: تعذرت قراءة الصورة، أو الفيديو ليس MP4/WebM/3GP/MOV/MKV، أو حجمه أكبر من 50MB (سقف الخطة المجانية)."
                        } catch (e: Exception) {
                            message = "تعذر الحفظ أو الرفع: " + (e.message ?: "خطأ غير معروف")
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("حفظ") }
        }
    }
}

@Composable
fun AdminNotifyScreen(onBack: () -> Unit) = AdminGate("إشعار عام", onBack) { r ->
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var newsId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ScreenScaffold(title = "إشعار عام", onBack = onBack) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("يصل لكل المستخدمين.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("العنوان") })
            OutlinedTextField(body, { body = it.take(500) }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("النص (اختياري)") })
            OutlinedTextField(newsId, { newsId = it.filter(Char::isDigit).take(9) }, Modifier.fillMaxWidth(), label = { Text("رقم خبر لفتحه (اختياري)") })
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (!confirm) {
                Button(enabled = !busy && title.isNotBlank(), onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("إرسال") }
            } else {
                Text("تأكيد الإرسال لكل المستخدمين؟", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        busy = true; confirm = false; message = null
                        scope.launch {
                            try {
                                r.broadcast(title, body, newsId.toIntOrNull())
                                title = ""; body = ""; newsId = ""
                                message = "أُرسل الإشعار."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "تعذر الإرسال. تحقق من الاتصال والصلاحية."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("نعم، أرسل") }
                    OutlinedButton(onClick = { confirm = false }) { Text("إلغاء") }
                }
            }
        }
    }
}
