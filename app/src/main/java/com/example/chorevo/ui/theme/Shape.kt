package com.example.chorevo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Rounded, soft corners used across cards, sheets, dialogs and fields. */
val ChorevoShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Fully rounded shape for pills: buttons, chips and the bottom bar. */
val PillShape = RoundedCornerShape(percent = 50)

/** Spacing scale (4dp grid). Prefer these over ad-hoc dp values in new UI. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Horizontal gutter for screen content. */
    val screenGutter = 16.dp
}
