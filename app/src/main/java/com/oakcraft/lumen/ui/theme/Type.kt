package com.oakcraft.lumen.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/**
 * The stock Material scale, tightened in two places: headlines carry more weight so
 * screen titles hold their own against the large numbers on the shield dashboard, and
 * the "stat" style below is wide-tracked for the counters themselves.
 */
val LumenTypography = Typography(
    headlineLarge = Default.headlineLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelLarge = Default.labelLarge.copy(letterSpacing = 0.2.sp)
)

val StatNumberStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
    letterSpacing = (-1).sp
)
