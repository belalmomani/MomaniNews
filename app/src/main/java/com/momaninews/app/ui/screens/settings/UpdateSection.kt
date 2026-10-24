package com.momaninews.app.ui.screens.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.momaninews.app.data.update.InstallResult
import com.momaninews.app.data.update.UpdateInfo
import com.momaninews.app.data.update.UpdateRepository
import java.io.File
import kotlinx.coroutines.launch

private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data class Available(val info: UpdateInfo) : UpdateUi
    data class Downloading(val info: UpdateInfo, val pct: Int) : UpdateUi
    data class Ready(val info: UpdateInfo, val file: File, val note: String?) : UpdateUi
    data class Error(val msg: String) : UpdateUi
}

@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    val currentCode = remember {
        try {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        } catch (e: PackageManager.NameNotFoundException) { 1L }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("تحديث التطبيق", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        val s = ui
        when (s) {
            is UpdateUi.Idle -> Unit
            is UpdateUi.Checking -> Text("جارٍ البحث عن تحديثات…", style = MaterialTheme.typography.bodyMedium)
            is UpdateUi.UpToDate -> Text("أنت تستخدم أحدث إصدار.", style = MaterialTheme.typography.bodyMedium)
            is UpdateUi.Available -> {
                Text("إصدار جديد متاح: ${s.info.versionName}", style = MaterialTheme.typography.bodyMedium)
                if (s.info.notes.isNotBlank()) Text(s.info.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is UpdateUi.Downloading -> {
                Text("جارٍ التنزيل… ${s.pct}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { s.pct / 100f }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateUi.Ready -> {
                Text(s.note ?: "تم التنزيل والتحقق من الملف. اضغط للتثبيت.", style = MaterialTheme.typography.bodyMedium)
            }
            is UpdateUi.Error -> Text(s.msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        val busy = s is UpdateUi.Checking || s is UpdateUi.Downloading
        when (s) {
            is UpdateUi.Available -> OutlinedButton(
                onClick = {
                    val info = s.info
                    ui = UpdateUi.Downloading(info, 0)
                    scope.launch {
                        ui = try {
                            val f = UpdateRepository.download(context, info, currentCode) { r, t ->
                                val pct = if (t > 0) ((r * 100) / t).toInt().coerceIn(0, 100) else 0
                                if (ui is UpdateUi.Downloading && (ui as UpdateUi.Downloading).pct != pct) ui = UpdateUi.Downloading(info, pct)
                            }
                            UpdateUi.Ready(info, f, null)
                        } catch (e: Exception) {
                            UpdateUi.Error("تعذّر تنزيل التحديث أو فشل التحقق منه. لم يُثبَّت شيء.")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("تنزيل التحديث") }
            is UpdateUi.Ready -> OutlinedButton(
                onClick = {
                    ui = when (UpdateRepository.install(context, s.file)) {
                        InstallResult.STARTED -> s
                        InstallResult.NEEDS_PERMISSION -> UpdateUi.Ready(s.info, s.file, "اسمح للتطبيق بتثبيت التحديثات من الشاشة التي فُتحت، ثم ارجع واضغط «تثبيت» مجدداً.")
                        InstallResult.FAILED -> UpdateUi.Error("تعذّر فتح المثبّت.")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("تثبيت التحديث") }
            else -> Unit
        }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                ui = UpdateUi.Checking
                scope.launch {
                    ui = try {
                        val info = UpdateRepository.check(currentCode)
                        if (info == null) UpdateUi.UpToDate else UpdateUi.Available(info)
                    } catch (e: Exception) {
                        UpdateUi.Error("تعذّر الاتصال بخادم التحديثات. تحقق من الإنترنت وحاول لاحقاً.")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("البحث عن تحديثات") }
    }
}
