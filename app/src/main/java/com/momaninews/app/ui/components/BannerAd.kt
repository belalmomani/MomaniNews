package com.momaninews.app.ui.components

import android.view.LayoutInflater
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.momaninews.app.R

/**
 * بانر Start.io من ملف التخطيط res/layout/view_startapp_banner.xml.
 * يُوضع في bottomBar لكل شاشة؛ navigationBarsPadding يمنع تداخله مع شريط التنقل.
 */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        factory = { context ->
            LayoutInflater.from(context).inflate(R.layout.view_startapp_banner, null, false)
        }
    )
}
