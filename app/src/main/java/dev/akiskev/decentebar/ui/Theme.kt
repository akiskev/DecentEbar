package dev.akiskev.decentebar.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
        colorScheme = LightScheme,
        typography = Typography(),
        shapes = AppShapes,
        content = content
    )
}
