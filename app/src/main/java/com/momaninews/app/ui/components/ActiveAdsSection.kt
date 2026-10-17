package com.momaninews.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.momaninews.app.data.ads.ActiveAd
import com.momaninews.app.data.ads.ActiveAdsRepository
import kotlinx.coroutines.CancellationException

/** إعلانات المعلنين المعتمدة (غير إعلانات Start.io). تُوسَم بوضوح «إعلان». لا شيء يظهر إن لم توجد أو فشل التحميل. */
@Composable
fun ActiveAdsSection(modifier: Modifier = Modifier) {
    var ads by remember { mutableStateOf<List<ActiveAd>>(emptyList()) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        if (!ActiveAdsRepository.isAvailable) return@LaunchedEffect
        try {
            ads = ActiveAdsRepository.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // الإعلانات اختيارية: نتجاهل الفشل بصمت
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ads.forEach { ad ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("إعلان", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(ad.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (ad.body.isNotEmpty()) {
                        Text(ad.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (ad.contact.isNotEmpty()) {
                        Text("للتواصل: ${ad.contact}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ad.linkUrl?.let { url ->
                        OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text("فتح الرابط") }
                    }
                }
            }
        }
    }
}
