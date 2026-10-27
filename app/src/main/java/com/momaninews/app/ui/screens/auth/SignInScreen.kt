package com.momaninews.app.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.AuthRepository
import com.momaninews.app.data.auth.SignInCancelledException
import com.momaninews.app.ui.components.BannerAd
import com.momaninews.app.ui.components.DemoBanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    auth: AuthRepository = AuthRepositories.default
) {
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // يبدأ الدخول بحساب Google مباشرة (نافذة اختيار الحساب الأصلية) دون ضغطة إضافية.
    fun startSignIn() {
        loading = true
        error = null
        scope.launch {
            try {
                auth.signIn(context)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignInCancelledException) {
                loading = false // أغلق المستخدم النافذة: لا رسالة خطأ؛ يبقى الزر لإعادة المحاولة
            } catch (e: Exception) {
                error = "تعذر تسجيل الدخول. حاول مرة أخرى."
                loading = false
            }
        }
    }

    // مرة واحدة فقط لكل فتح للشاشة (لا يتكرر بالتدوير)، وليس في الوضع التجريبي ولا إن كان المستخدم مسجلاً أصلاً
    var autoTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoTried && !auth.isDemo && auth.currentUser.value == null) {
            autoTried = true
            startSignIn()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BannerAd() },
        topBar = {
            TopAppBar(
                title = { Text(text = "تسجيل الدخول", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
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
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (auth.isDemo) {
                DemoBanner()
            }

            Text(
                text = "سجّل الدخول للتعليق والتصويت على مصداقية الأخبار.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = { startSignIn() },
                enabled = !loading
            ) {
                Text(
                    text = when {
                        loading -> "جارٍ الدخول…"
                        auth.isDemo -> "دخول تجريبي"
                        else -> "الدخول بحساب Google"
                    }
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Text(
                text = if (auth.isDemo) {
                    "الدخول الحالي تجريبي محلي في الذاكرة فقط ولا يحفظ أي بيانات."
                } else {
                    "نستخدم حساب Google للتعرّف عليك فقط (الاسم والبريد والصورة). لا نصل إلى أي شيء آخر في حسابك."
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
