package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AmktDarkColorScheme =
  darkColorScheme(
    primary = JadePrimary,
    onPrimary = JadeOnPrimary,
    primaryContainer = JadePrimaryContainer,
    onPrimaryContainer = JadeOnPrimaryContainer,
    secondary = AmberSecondary,
    onSecondary = AmberOnSecondary,
    secondaryContainer = AmberSecondaryContainer,
    onSecondaryContainer = AmberOnSecondaryContainer,
    tertiary = CyanTertiary,
    onTertiary = CyanOnTertiary,
    tertiaryContainer = CyanTertiaryContainer,
    onTertiaryContainer = CyanOnTertiaryContainer,
    background = ObsidianBg,
    onBackground = InkPrimaryDark,
    surface = ObsidianSurface,
    onSurface = InkPrimaryDark,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = InkSecondaryDark,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceHigh,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = CoralError,
    onError = CoralOnError,
    errorContainer = CoralErrorContainer,
    onErrorContainer = CoralOnErrorContainer,
  )

private val AmktLightColorScheme =
  lightColorScheme(
    primary = JadeLightPrimary,
    onPrimary = Color.White,
    primaryContainer = JadeLightContainer,
    onPrimaryContainer = JadeOnPrimary,
    secondary = AmberLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = AmberLightContainer,
    onSecondaryContainer = AmberOnSecondary,
    tertiary = CyanLightTertiary,
    onTertiary = Color.White,
    tertiaryContainer = CyanLightContainer,
    onTertiaryContainer = CyanOnTertiary,
    background = SlateLightBg,
    onBackground = InkPrimaryLight,
    surface = SlateLightSurface,
    onSurface = InkPrimaryLight,
    surfaceVariant = SlateLightSurfaceVariant,
    onSurfaceVariant = InkSecondaryLight,
    outline = OutlineLight,
    error = Color(0xFFC62828),
    onError = Color.White,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) AmktDarkColorScheme else AmktLightColorScheme
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
