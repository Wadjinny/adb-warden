package com.wadjinny.adbwarden.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** "Soft & Rounded" design tokens (light + dark). */
data class Tokens(
    val dark: Boolean,
    val gradStart: Color, val gradEnd: Color, val headerStart: Color, val headerEnd: Color,
    val indigo: Color, val indigoSoft: Color, val violet: Color, val violetSoft: Color, val violetText: Color,
    val card: Color, val bg: Color,
    val g100: Color, val g200: Color, val g300: Color, val g400: Color, val g500: Color, val g700: Color, val g900: Color,
    val okBg: Color, val okFg: Color, val warnBg: Color, val warnFg: Color, val errBg: Color, val errFg: Color,
    val pink: Color, val green: Color, val amber: Color, val sky: Color,
)

val LightTokens = Tokens(
    false,
    Color(0xFF6366F1), Color(0xFFA855F7), Color(0xFF4F46E5), Color(0xFF9333EA),
    Color(0xFF4F46E5), Color(0xFFEEF2FF), Color(0xFF7C3AED), Color(0xFFF3E8FF), Color(0xFF7E22CE),
    Color(0xFFFFFFFF), Color(0xFFF5F5FB),
    Color(0xFFEEF0FB), Color(0xFFE5E7EB), Color(0xFFD1D5DB), Color(0xFF9CA3AF), Color(0xFF6B7280), Color(0xFF374151), Color(0xFF1E1B4B),
    Color(0xFFDCFCE7), Color(0xFF15803D), Color(0xFFFEF3C7), Color(0xFFB45309), Color(0xFFFFE4E6), Color(0xFFBE123C),
    Color(0xFFDB2777), Color(0xFF16A34A), Color(0xFFD97706), Color(0xFF0284C7),
)

val DarkTokens = Tokens(
    true,
    Color(0xFF818CF8), Color(0xFFC084FC), Color(0xFF3730A3), Color(0xFF7E22CE),
    Color(0xFFA5B4FC), Color(0xFF2A2650), Color(0xFFC4B5FD), Color(0xFF2E2650), Color(0xFFD8B4FE),
    Color(0xFF1B1A2E), Color(0xFF0F0E1A),
    Color(0xFF26243D), Color(0xFF34324F), Color(0xFF4B4868), Color(0xFF8C89AA), Color(0xFFA5A3C4), Color(0xFFD6D4EE), Color(0xFFEDEBFF),
    Color(0xFF14352A), Color(0xFF4ADE80), Color(0xFF3A2E12), Color(0xFFFBBF24), Color(0xFF3D1A22), Color(0xFFFB7185),
    Color(0xFFF472B6), Color(0xFF4ADE80), Color(0xFFFBBF24), Color(0xFF38BDF8),
)

val LocalTokens = staticCompositionLocalOf { LightTokens }

@Composable
fun WardenTheme(content: @Composable () -> Unit) {
    val t = if (isSystemInDarkTheme()) DarkTokens else LightTokens
    val scheme = if (t.dark) darkColorScheme(
        primary = t.violet, onPrimary = Color.White, background = t.bg, surface = t.card, onSurface = t.g900,
        onBackground = t.g900, surfaceVariant = t.g100, onSurfaceVariant = t.g500, outline = t.g200,
        primaryContainer = t.violetSoft, onPrimaryContainer = t.violetText, error = t.errFg,
        surfaceContainerHigh = t.card, surfaceContainer = t.card, surfaceContainerHighest = t.g100,
    ) else lightColorScheme(
        primary = t.violet, onPrimary = Color.White, background = t.bg, surface = t.card, onSurface = t.g900,
        onBackground = t.g900, surfaceVariant = t.g100, onSurfaceVariant = t.g500, outline = t.g200,
        primaryContainer = t.violetSoft, onPrimaryContainer = t.violetText, error = t.errFg,
        surfaceContainerHigh = t.card, surfaceContainer = t.card, surfaceContainerHighest = t.g100,
    )
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
