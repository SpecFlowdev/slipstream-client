package dev.specflow.slipstream.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.specflow.slipstream.core.Theme

/**
 * Three palettes, the same three the desktop client offers: a near-black
 * graphite, a paper white, and the navy the project started in.
 *
 * They are defined here rather than taken from the system so a screenshot of
 * one client looks like a screenshot of the other, and so the wallpaper layer
 * underneath has known colours to sit against.
 */

private val Navy = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF06122B),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111C33),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1B2A47),
    onSurfaceVariant = Color(0xFF9FB3D1),
    outline = Color(0xFF2C3E60),
    error = Color(0xFFFCA5A5),
)

private val Graphite = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF1F2937),
    onPrimaryContainer = Color(0xFFE5E7EB),
    secondary = Color(0xFFA5B4FC),
    background = Color(0xFF0C0C0E),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF16171A),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF222429),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF33363D),
    error = Color(0xFFFCA5A5),
)

private val Paper = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B1120),
    secondary = Color(0xFF0369A1),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEDF0F5),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFD3D9E3),
    error = Color(0xFFB91C1C),
)

/** Numbers line up in columns only if their digits are the same width. */
val Tabular = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
)

val LocalTheme = staticCompositionLocalOf { Theme.DARK }

@Composable
fun SlipstreamTheme(theme: Theme, content: @Composable () -> Unit) {
    val scheme = when (theme) {
        Theme.DARK -> Graphite
        Theme.BLUE -> Navy
        Theme.LIGHT -> Paper
    }
    CompositionLocalProvider(LocalTheme provides theme) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(),
            content = content
        )
    }
}

/** True when the palette in use is a dark one, which a few overlays need. */
@Composable
fun isDarkPalette(): Boolean = when (LocalTheme.current) {
    Theme.LIGHT -> false
    else -> true
}

@Composable
fun systemPrefersDark(): Boolean = isSystemInDarkTheme()
