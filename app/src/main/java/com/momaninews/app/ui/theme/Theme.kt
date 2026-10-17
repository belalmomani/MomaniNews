package com.momaninews.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momaninews.app.data.settings.SettingsStore
import com.momaninews.app.data.settings.ThemeMode

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

// ألوان الوضع الداكن: زيتوني هادئ على خلفية بنية داكنة (تباين النص > 7:1 على الخلفية).
private val DarkColors = darkColorScheme(
    primary = Color(0xFF6F7F3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B4420),
    onPrimaryContainer = Color(0xFFE2E8C4),
    secondary = Color(0xFFC9A66B),
    onSecondary = Color(0xFF2A1F0E),
    secondaryContainer = Color(0xFF4A3B22),
    onSecondaryContainer = Color(0xFFF0E2C8),
    background = Color(0xFF1C1A16),
    onBackground = Color(0xFFEDE6D9),
    surface = Color(0xFF26231E),
    onSurface = Color(0xFFEDE6D9),
    surfaceVariant = Color(0xFF312D26),
    onSurfaceVariant = Color(0xFFCFC5B2),
    outline = Color(0xFF6B6354),
    error = Color(0xFFFF8A80)
)

@Composable
fun MomaniNewsTheme(content: @Composable () -> Unit) {
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = LocalDensity.current
    val density = Density(base.density, base.fontScale * settings.textScale)
    // التطبيق عربي بالكامل: نفرض الاتجاه من اليمين إلى اليسار بغض النظر عن لغة الجهاز.
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalDensity provides density
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            content = content
        )
    }
}
