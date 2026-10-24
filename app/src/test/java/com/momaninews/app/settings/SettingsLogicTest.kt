package com.momaninews.app.settings

import com.momaninews.app.data.settings.TextScales
import com.momaninews.app.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLogicTest {
    @Test fun parseKnownThemes() {
        assertEquals(ThemeMode.DARK, ThemeMode.parse("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse("LIGHT"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("SYSTEM"))
    }

    @Test fun parseUnknownThemeFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("PURPLE"))
    }

    @Test fun normalizeKeepsAllowedValues() {
        TextScales.options.forEach { assertEquals(it, TextScales.normalize(it)) }
    }

    @Test fun normalizeSnapsToNearestOption() {
        assertEquals(1.0f, TextScales.normalize(1.02f))
        assertEquals(1.3f, TextScales.normalize(5f))
        assertEquals(0.9f, TextScales.normalize(0f))
        assertEquals(1.15f, TextScales.normalize(1.2f))
    }

    @Test fun normalizeRejectsGarbage() {
        assertEquals(TextScales.DEFAULT, TextScales.normalize(Float.NaN))
        assertEquals(TextScales.DEFAULT, TextScales.normalize(Float.POSITIVE_INFINITY))
    }

    @Test fun labelsAreDistinct() {
        assertEquals(TextScales.options.size, TextScales.options.map { TextScales.label(it) }.toSet().size)
    }
}
