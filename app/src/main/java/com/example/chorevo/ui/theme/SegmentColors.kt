package com.example.chorevo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

object SegmentColors {
    val good: Color
        @Composable @ReadOnlyComposable get() =
            if (isSystemInDarkTheme())
                Color(0xFF81C784)   // lighter green — readable on dark backgrounds
            else
                Color(0xFF2E7D32)   // darker green — readable on light backgrounds

    val ok: Color
        @Composable @ReadOnlyComposable get() =
            if (isSystemInDarkTheme())
                Color(0xFFFFD54F)   // lighter amber on dark
            else
                Color(0xFFF57F17)   // darker amber on light

    val bad: Color
        @Composable @ReadOnlyComposable get() =
            if (isSystemInDarkTheme())
                Color(0xFFEF9A9A)   // lighter red on dark
            else
                Color(0xFFC62828)   // darker red on light
}