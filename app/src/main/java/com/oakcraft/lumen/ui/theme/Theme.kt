package com.oakcraft.lumen.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = IndigoPrimary,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = IndigoOnContainer,
    secondary = AmberSecondary,
    secondaryContainer = AmberContainer,
    tertiary = TealTertiary,
    tertiaryContainer = TealContainer,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceContainer = SurfaceContainerLight,
    outline = OutlineLight,
    error = DangerLight
)

private val DarkColors = darkColorScheme(
    primary = IndigoPrimaryDark,
    primaryContainer = IndigoContainerDark,
    secondary = AmberSecondaryDark,
    secondaryContainer = AmberContainerDark,
    tertiary = TealTertiaryDark,
    tertiaryContainer = TealContainerDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceContainer = SurfaceContainerDark,
    outline = OutlineDark,
    error = DangerDark
)

@Composable
fun LumenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = LumenTypography,
        content = content
    )
}
