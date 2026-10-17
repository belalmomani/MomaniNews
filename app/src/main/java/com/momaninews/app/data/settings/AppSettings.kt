package com.momaninews.app.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK;
    companion object {
        /** قيمة غير معروفة أو null ترجع إلى SYSTEM (لا ينهار التطبيق على تفضيل تالف). */
        fun parse(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** أحجام الخط المتاحة (مضاعِف فوق حجم خط النظام). */
object TextScales {
    val options: List<Float> = listOf(0.9f, 1.0f, 1.15f, 1.3f)
    const val DEFAULT = 1.0f

    /** يُرجع أقرب خيار مسموح؛ NaN/غير منطقي يرجع للافتراضي. */
    fun normalize(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return DEFAULT
        return options.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT
    }

    fun label(scale: Float): String = when (normalize(scale)) {
        0.9f -> "صغير"
        1.15f -> "كبير"
        1.3f -> "كبير جداً"
        else -> "عادي"
    }
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val textScale: Float = TextScales.DEFAULT
)

/** تفضيلات محلية بسيطة (لا بيانات حساسة). [init] من Application.onCreate؛ قبلها تُرجع الافتراضيات. */
object SettingsStore {
    private const val FILE = "app_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_SCALE = "text_scale"

    private val state = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        state.value = AppSettings(
            theme = ThemeMode.parse(prefs.getString(KEY_THEME, null)),
            textScale = TextScales.normalize(prefs.getFloat(KEY_SCALE, TextScales.DEFAULT))
        )
    }

    fun setTheme(mode: ThemeMode) {
        state.value = state.value.copy(theme = mode)
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString(KEY_THEME, mode.name)?.apply()
    }

    fun setTextScale(scale: Float) {
        val s = TextScales.normalize(scale)
        state.value = state.value.copy(textScale = s)
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putFloat(KEY_SCALE, s)?.apply()
    }
}
