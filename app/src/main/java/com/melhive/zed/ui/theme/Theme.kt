package com.melhive.zed.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object ZedColors {
    val Background = Color(0xFF05070D)
    val Surface = Color(0xFF0B111E)
    val SurfaceHigh = Color(0xFF111A2D)
    val Outline = Color(0xFF1E2A44)
    val Cyan = Color(0xFF5CE1FF)
    val Violet = Color(0xFF7B6CFF)
    val Teal = Color(0xFF3DFFC8)
    val Amber = Color(0xFFFFB547)
    val Green = Color(0xFF4CFFA0)
    val Red = Color(0xFFFF5C7A)
    val Text = Color(0xFFE6ECFF)
    val Muted = Color(0xFF8A94B8)
}

private val scheme = darkColorScheme(
    primary = ZedColors.Cyan,
    onPrimary = Color(0xFF00141B),
    primaryContainer = Color(0xFF0E3846),
    onPrimaryContainer = ZedColors.Cyan,
    secondary = ZedColors.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF241F52),
    onSecondaryContainer = Color(0xFFD9D4FF),
    tertiary = ZedColors.Teal,
    background = ZedColors.Background,
    onBackground = ZedColors.Text,
    surface = ZedColors.Surface,
    onSurface = ZedColors.Text,
    surfaceVariant = ZedColors.SurfaceHigh,
    onSurfaceVariant = ZedColors.Muted,
    surfaceContainer = ZedColors.Surface,
    surfaceContainerHigh = ZedColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF16213A),
    surfaceContainerLow = Color(0xFF080D18),
    outline = ZedColors.Outline,
    outlineVariant = Color(0xFF17213A),
    error = ZedColors.Red,
    onError = Color.White,
)

private val base = Typography()
private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Light, letterSpacing = 6.sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Light, letterSpacing = 2.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Normal, letterSpacing = 1.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(letterSpacing = 1.5.sp),
    bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun ZedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
