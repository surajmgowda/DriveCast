package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val VideoDeckDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianBg,
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = OnDeepCyan,
    secondary = WindsorEmerald,
    onSecondary = ObsidianBg,
    secondaryContainer = DeepEmeraldContainer,
    onSecondaryContainer = OnDeepEmerald,
    tertiary = AmberWarning,
    onTertiary = ObsidianBg,
    tertiaryContainer = DeepAmberContainer,
    onTertiaryContainer = TextPrimary,
    error = CoralAlert,
    onError = TextPrimary,
    errorContainer = DeepCoralContainer,
    onErrorContainer = TextPrimary,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurfaceVariant,
    surfaceContainerHigh = SlateElevated,
    outline = SlateOutline
)

private val VideoDeckShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun VideoDeckTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VideoDeckDarkColorScheme,
        typography = VideoDeckTypography,
        shapes = VideoDeckShapes,
        content = content
    )
}
