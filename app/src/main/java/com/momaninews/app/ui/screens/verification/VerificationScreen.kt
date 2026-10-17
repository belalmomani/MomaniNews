package com.momaninews.app.ui.screens.verification

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.model.VerificationStatus
import com.momaninews.app.data.verification.ImagePrep
import com.momaninews.app.data.verification.VerificationException
import com.momaninews.app.data.verification.VerificationRepositories
import com.momaninews.app.data.verification.VerificationRequestInfo
import com.momaninews.app.data.verification.VerificationRules
import com.momaninews.app.ui.components.BannerAd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationScreen(onBack: () -> Unit) {
    val auth = AuthRepositories.default
    val repo = VerificationRepositories.default
    val user by auth.currentUser.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var latest by remember { mutableStateOf<VerificationRequestInfo?>(null) }
    var name by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    // الصورة تُختار عبر منتقي النظام (لا حاجة لصلاحية قراءة الملفات).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) imageUri = uri
    }

    LaunchedEffect(user?.id, reload) {
        if (repo == null || user == null) { loading = false; return@LaunchedEffect }
        loading = true
        try {
            latest = repo.latest()
            message = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "تعذر تحميل حالة الطلب."
        }
        loading = false
    }

    fun submit() {
        val uri = imageUri ?: return
        if (repo == null) return
        busy = true
        message = null
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) { ImagePrep.toJpeg(context, uri) }
                    ?: throw VerificationException(VerificationException.Reason.INVALID_IMAGE)
                repo.submit(name, jpeg)
                auth.refreshProfile()
                name = ""
                imageUri = null
                reload++
            } catch (e: CancellationException) {
                throw e
            } catch (e: VerificationException) {
                message = when (e.reason) {
                    VerificationException.Reason.INVALID_NAME -> "اكتب الاسم الكامل (حرفان على الأقل)."
                    VerificationException.Reason.INVALID_IMAGE -> "تعذر استخدام هذه الصورة. اختر صورة أخرى."
                    VerificationException.Reason.ALREADY_PENDING -> "لديك طلب قيد المراجعة أو أنت موثّق بالفعل."
                    else -> "تعذر إرسال الطلب. حاول مرة أخرى."
                }
            } catch (e: Exception) {
                message = "تعذر إرسال الطلب. تحقق من الاتصال وحاول مرة أخرى."
            }
            busy = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BannerAd() },
        topBar = {
            TopAppBar(
                title = { Text(text = "طلب التوثيق", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                repo == null -> Text(
                    "غير متاح في الوضع التجريبي: التوثيق يحتاج تسجيل الدخول بحساب Google الحقيقي.",
                    style = MaterialTheme.typography.bodyLarge
                )
                user == null -> Text("سجّل الدخول أولاً من صفحة الحساب.", style = MaterialTheme.typography.bodyLarge)
                loading -> Text("جارٍ التحميل…", style = MaterialTheme.typography.bodyMedium)
                user?.verification == VerificationStatus.APPROVED -> Text(
                    "حسابك موثّق.", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                user?.verification == VerificationStatus.PENDING -> Text(
                    "طلبك قيد المراجعة" + (latest?.dateLabel?.takeIf { it.isNotEmpty() }?.let { " (قُدّم في $it)" } ?: "") +
                        ". سيتغيّر وضع حسابك عند اعتماده.",
                    style = MaterialTheme.typography.bodyLarge
                )
                else -> {
                    if (latest?.status == VerificationStatus.REJECTED) {
                        Text(
                            "رُفض طلبك السابق" + (latest?.adminNote?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "") +
                                ". يمكنك تقديم طلب جديد.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        "التوثيق يؤكد أنك من أهل المنطقة. صورة الهوية تُرفع إلى مخزن خاص، لا يراها إلا المدير، " +
                            "ولا تُعرض لأي مستخدم. نُعيد ضغط الصورة قبل الرفع فتُحذف بيانات الموقع منها.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(VerificationRules.MAX_NAME) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("الاسم الكامل كما في الهوية") },
                        singleLine = true
                    )
                    OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
                        Text(if (imageUri == null) "اختيار صورة الهوية" else "تغيير الصورة (تم اختيار صورة)")
                    }
                    Button(
                        onClick = { submit() },
                        enabled = !busy && imageUri != null && VerificationRules.cleanName(name) != null
                    ) {
                        Text(if (busy) "جارٍ الإرسال…" else "إرسال الطلب")
                    }
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
