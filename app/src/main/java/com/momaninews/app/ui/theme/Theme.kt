package com.momaninews.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val LightColors = lightColorScheme(
    primary = Olive,
    onPrimary = Color.White,
    primaryContainer = OliveContainer,
    onPrimaryContainer = OnOliveContainer,
    secondary = Tan,
    onSecondary = Color.White,
    secondaryContainer = TanContainer,
    onSecondaryContainer = BrownText,
    background = Paper,
    onBackground = BrownText,
    surface = Color.White,
    onSurface = BrownText,
    surfaceVariant = Cream,
    onSurfaceVariant = BrownMuted,
    outline = OutlineSand,
    error = ErrorRed
)

@Composable
fun MomaniNewsTheme(content: @Composable () -> Unit) {
    // التطبيق عربي بالكامل: نفرض الاتجاه من اليمين إلى اليسار بغض النظر عن لغة الجهاز.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = LightColors,
            typography = AppTypography,
            content = content
        )
    }
}
