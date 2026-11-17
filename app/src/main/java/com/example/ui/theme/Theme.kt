package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
  darkColorScheme(
    primary = PhosphorEmeraldBright,
    onPrimary = OnEmeraldDark,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = PhosphorEmeraldBright,
    secondary = CyanSignal,
    onSecondary = Color(0xFF042233),
    secondaryContainer = CyanContainer,
    onSecondaryContainer = CyanSignal,
    tertiary = AmberWarn,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateElevated,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    error = CoralError,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    background = LightBg,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = Color(0xFFCBD5E1),
    error = CoralError,
  )

val TermuxCastShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
  )

@Composable
fun TermuxCastTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = TermuxCastTypography,
    shapes = TermuxCastShapes,
    content = content,
  )
}
