package dev.akiskev.decentebar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF24545A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EFF1),
    onPrimaryContainer = Color(0xFF062B30),
    secondary = Color(0xFF81572A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB6),
    onSecondaryContainer = Color(0xFF2E1700),
    tertiary = Color(0xFF52642F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6E9A8),
    onTertiaryContainer = Color(0xFF151F00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    background = Color(0xFFF8FAF7),
    onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFF8FAF7),
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFDEE5E2),
    onSurfaceVariant = Color(0xFF424A48),
    outline = Color(0xFF727B78)
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8DD7DB),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF005158),
    onPrimaryContainer = Color(0xFFB8F2F4),

    secondary = Color(0xFFE7BC82),
    onSecondary = Color(0xFF432B09),
    secondaryContainer = Color(0xFF62400F),
    onSecondaryContainer = Color(0xFFFFDDA6),

    tertiary = Color(0xFFC9D6A2),
    onTertiary = Color(0xFF31380F),
    tertiaryContainer = Color(0xFF475120),
    onTertiaryContainer = Color(0xFFE5F2BD),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF101414),
    onBackground = Color(0xFFE1E6E3),

    surface = Color(0xFF141918),
    onSurface = Color(0xFFE1E6E3),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),

    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),

    surfaceContainerLowest = Color(0xFF0B0F0F),
    surfaceContainerLow = Color(0xFF151B1A),
    surfaceContainer = Color(0xFF19201F),
    surfaceContainerHigh = Color(0xFF202827),
    surfaceContainerHighest = Color(0xFF2A3331),

    inverseSurface = Color(0xFFE1E6E3),
    inverseOnSurface = Color(0xFF2E3130),
    inversePrimary = Color(0xFF006971)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun DecentebarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = Typography(),
        shapes = AppShapes,
        content = content
    )
}
