package com.momaninews.app.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.data.auth.AuthRepositories
import com.momaninews.app.data.auth.AuthRepository
import com.momaninews.app.data.model.VerificationStatus
import com.momaninews.app.ui.components.BannerAd
import com.momaninews.app.ui.components.DemoBanner
import com.momaninews.app.ui.components.MessageState

private fun verificationLabel(status: VerificationStatus): String = when (status) {
    VerificationStatus.NONE -> "غير موثّق"
    VerificationStatus.PENDING -> "طلب التوثيق قيد المراجعة"
    VerificationStatus.APPROVED -> "موثّق"
    VerificationStatus.REJECTED -> "رُفض طلب التوثيق"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSignInClick: () -> Unit,
    onVerificationClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onAdRequestClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAdminClick: () -> Unit,
    auth: AuthRepository = AuthRepositories.default
) {
    val user by auth.currentUser.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BannerAd() },
        topBar = {
            TopAppBar(
                title = { Text(text = "الحساب", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "الإعدادات")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val current = user
            if (current == null) {
                MessageState(
                    title = "لم تسجّل الدخول",
                    message = "سجّل الدخول للتعليق والتصويت على مصداقية الأخبار.",
                    actionLabel = "تسجيل الدخول",
                    onAction = onSignInClick
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (current.isDemo) {
                        DemoBanner()
                    }
                    Text(
                        text = current.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = current.email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "حالة التوثيق: " + verificationLabel(current.verification),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (!current.isDemo && current.verification != VerificationStatus.APPROVED) {
                        Button(onClick = onVerificationClick) {
                            Text(text = if (current.verification == VerificationStatus.NONE) "طلب التوثيق" else "حالة طلب التوثيق")
                        }
                    }
                    OutlinedButton(onClick = onNotificationsClick) { Text(text = "الإشعارات") }
                    OutlinedButton(onClick = onAdRequestClick) { Text(text = "أعلن معنا") }
                    if (current.isAdmin) {
                        Button(onClick = onAdminClick) { Text(text = "لوحة الإدارة") }
                    }
                    OutlinedButton(onClick = { auth.signOut() }) {
                        Text(text = "تسجيل الخروج")
                    }
                }
            }
        }
    }
}
