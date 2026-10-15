package com.example.chorevo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.chorevo.R

/** Display face for headings, titles and numbers (Sora, SIL OFL 1.1). */
val SoraFontFamily = FontFamily(
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_bold, FontWeight.Bold),
    Font(R.font.sora_extrabold, FontWeight.ExtraBold)
)

/** Body face for text and controls (Plus Jakarta Sans, SIL OFL 1.1). */
val JakartaFontFamily = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold)
)

private fun display(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = SoraFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

private fun body(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = JakartaFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

val Typography = Typography(
    displayLarge = display(52, 60, FontWeight.ExtraBold, -1.0),
    displayMedium = display(42, 50, FontWeight.ExtraBold, -0.75),
    displaySmall = display(34, 42, FontWeight.Bold, -0.5),
    headlineLarge = display(30, 38, FontWeight.Bold, -0.5),
    headlineMedium = display(26, 34, FontWeight.Bold, -0.25),
    headlineSmall = display(22, 30, FontWeight.Bold),
    titleLarge = display(20, 28, FontWeight.SemiBold),
    titleMedium = body(16, 24, FontWeight.Bold, 0.1),
    titleSmall = body(14, 20, FontWeight.Bold, 0.1),
    bodyLarge = body(16, 24, FontWeight.Normal, 0.15),
    bodyMedium = body(14, 20, FontWeight.Normal, 0.15),
    bodySmall = body(12, 16, FontWeight.Normal, 0.2),
    labelLarge = body(14, 20, FontWeight.SemiBold, 0.1),
    labelMedium = body(12, 16, FontWeight.SemiBold, 0.3),
    labelSmall = body(11, 16, FontWeight.SemiBold, 0.4)
)
