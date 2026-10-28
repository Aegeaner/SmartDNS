package dev.smartdns.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9EAFF),
    onPrimaryContainer = Color(0xFF1B1C4B),
    secondaryContainer = Color(0xFFF1F1F4),
    onSecondaryContainer = Color(0xFF3A3A44),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF141418),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141418),
    surfaceVariant = Color(0xFFF3F3F6),
    onSurfaceVariant = Color(0xFF6C6C77),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color(0xFFF7F7F9),
    surfaceContainerHigh = Color(0xFFF2F2F5),
    surfaceContainerHighest = Color(0xFFEDEDF1),
    outline = Color(0xFFE2E2E8),
    outlineVariant = Color(0xFFEFEFF3),
    inverseSurface = Color(0xFF2A2A31),
    inverseOnSurface = Color(0xFFF4F4F6),
    inversePrimary = Color(0xFFB9BCFF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCECEA),
    onErrorContainer = Color(0xFF601410),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9B0FF),
    onPrimary = Color(0xFF1B1C4B),
    primaryContainer = Color(0xFF2B2C4A),
    onPrimaryContainer = Color(0xFFE6E7FF),
    secondaryContainer = Color(0xFF26262E),
    onSecondaryContainer = Color(0xFFD9D9E0),
    background = Color(0xFF0F0F12),
    onBackground = Color(0xFFECECEF),
    surface = Color(0xFF0F0F12),
    onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF1D1D23),
    onSurfaceVariant = Color(0xFF9E9EA9),
    surfaceContainerLowest = Color(0xFF0A0A0D),
    surfaceContainerLow = Color(0xFF131317),
    surfaceContainer = Color(0xFF17171C),
    surfaceContainerHigh = Color(0xFF1D1D24),
    surfaceContainerHighest = Color(0xFF25252E),
    outline = Color(0xFF2E2E37),
    outlineVariant = Color(0xFF232330),
    inverseSurface = Color(0xFFECECEF),
    inverseOnSurface = Color(0xFF1A1A20),
    inversePrimary = Color(0xFF4F46E5),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF3A1B19),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SmartDnsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
