package app.avoska.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours the Material scheme has no slot for: file state text and the progress track. */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val progressTrack: Color)

// Values from docs/design/mockups: one blue accent, neutral surfaces.
private val Light = lightColorScheme(
    primary = Color(0xFF2B5FC7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E4FB),
    onPrimaryContainer = Color(0xFF1A1C20),
    secondary = Color(0xFF2B5FC7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E4FB),
    onSecondaryContainer = Color(0xFF1A1C20),
    tertiary = Color(0xFF1E6B3F),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE6ECF8),
    onSurfaceVariant = Color(0xFF5A5F6B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFE6ECF8),
    surfaceContainerHigh = Color(0xFFE6ECF8),
    surfaceContainerHighest = Color(0xFFE1E4EC),
    outline = Color(0xFF747A86),
    outlineVariant = Color(0xFFDDE2EC),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C4FF),
    onPrimary = Color(0xFF0B2B66),
    primaryContainer = Color(0xFF243352),
    onPrimaryContainer = Color(0xFFE3E5EA),
    secondary = Color(0xFFA9C4FF),
    onSecondary = Color(0xFF0B2B66),
    secondaryContainer = Color(0xFF243352),
    onSecondaryContainer = Color(0xFFE3E5EA),
    tertiary = Color(0xFF7FD6A0),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E5EA),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E5EA),
    surfaceVariant = Color(0xFF1E222B),
    onSurfaceVariant = Color(0xFFA4AAB6),
    surfaceContainerLowest = Color(0xFF1B1F27),
    surfaceContainerLow = Color(0xFF1B1F27),
    surfaceContainer = Color(0xFF1E222B),
    surfaceContainerHigh = Color(0xFF1E222B),
    surfaceContainerHighest = Color(0xFF2A2F39),
    outline = Color(0xFF8D93A0),
    outlineVariant = Color(0xFF2A2F39),
    error = Color(0xFFF2B8B5),
)

private val LightStatus = StatusColors(ok = Color(0xFF1E6B3F), warn = Color(0xFF8F4400), progressTrack = Color(0xFFC9D3E8))
private val DarkStatus = StatusColors(ok = Color(0xFF7FD6A0), warn = Color(0xFFFFB877), progressTrack = Color(0xFF2F3644))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Follows the system theme. Dynamic colour is off so the accent stays the same on every device. */
@Composable
fun AvoskaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme: ColorScheme = if (darkTheme) Dark else Light
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
