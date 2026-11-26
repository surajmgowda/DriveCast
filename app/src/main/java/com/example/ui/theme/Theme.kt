package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val VideoDeckDarkColorScheme = darkColorScheme(
    primary = SolarCopper,
    onPrimary = CarbonVoidBg,
    primaryContainer = DeepBronzeContainer,
    onPrimaryContainer = OnDeepBronze,
    secondary = TelemetryJade,
    onSecondary = CarbonVoidBg,
    secondaryContainer = DeepJadeContainer,
    onSecondaryContainer = OnDeepJade,
    tertiary = AcousticSky,
    onTertiary = CarbonVoidBg,
    tertiaryContainer = DeepSkyContainer,
    onTertiaryContainer = OnDeepSky,
    error = CrimsonAlert,
    onError = IvoryPrimary,
    errorContainer = DeepCrimsonContainer,
    onErrorContainer = IvoryPrimary,
    background = CarbonVoidBg,
    onBackground = IvoryPrimary,
    surface = SmokedGraphite,
    onSurface = IvoryPrimary,
    surfaceVariant = TitaniumSurface,
    onSurfaceVariant = SilverSecondary,
    surfaceContainerLow = SmokedGraphite,
    surfaceContainer = TitaniumSurface,
    surfaceContainerHigh = BrushedSlate,
    outline = HairlineTitanium
)

private val VideoDeckShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
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
