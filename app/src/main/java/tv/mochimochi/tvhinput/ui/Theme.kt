package tv.mochimochi.tvhinput.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Dark scheme built around the launcher icon's navy (#1E3A5F) and light blue (#4FC3F7). */
private val TvhColors = darkColorScheme(
    primary = Color(0xFF8ED0F5),
    onPrimary = Color(0xFF003548),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFFB7C8DA),
    onSecondary = Color(0xFF213240),
    secondaryContainer = Color(0xFF374857),
    onSecondaryContainer = Color(0xFFD3E4F7),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E2E8),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE0E2E8),
    surfaceVariant = Color(0xFF262A2F),
    onSurfaceVariant = Color(0xFFC1C7CE),
    inverseSurface = Color(0xFFE0E2E8),
    inverseOnSurface = Color(0xFF2D3135),
    border = Color(0xFF8B9198),
    borderVariant = Color(0xFF41484D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun TvhTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvhColors, content = content)
}
