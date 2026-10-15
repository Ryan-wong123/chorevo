package com.example.chorevo.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import com.example.chorevo.ui.theme.ChorevoTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pose keyframes for the looping dancer, as (x, y) pairs in a 0–1 box:
 * head, neck, L/R shoulder, L/R elbow, L/R wrist, L/R hip, L/R knee, L/R ankle.
 * "L" is the dancer's left, drawn on the viewer's right.
 */
private val POSE_ARMS_UP = floatArrayOf(
    0.50f, 0.12f, 0.50f, 0.22f, 0.60f, 0.25f, 0.40f, 0.25f, 0.70f, 0.14f, 0.30f, 0.14f,
    0.76f, 0.03f, 0.24f, 0.03f, 0.56f, 0.52f, 0.44f, 0.52f, 0.60f, 0.72f, 0.40f, 0.72f,
    0.62f, 0.93f, 0.38f, 0.93f
)
private val POSE_WAVE = floatArrayOf(
    0.53f, 0.13f, 0.52f, 0.23f, 0.62f, 0.25f, 0.42f, 0.26f, 0.75f, 0.20f, 0.33f, 0.38f,
    0.86f, 0.10f, 0.36f, 0.50f, 0.57f, 0.52f, 0.45f, 0.53f, 0.60f, 0.73f, 0.36f, 0.68f,
    0.62f, 0.94f, 0.42f, 0.82f
)
private val POSE_WIDE = floatArrayOf(
    0.50f, 0.17f, 0.50f, 0.27f, 0.60f, 0.30f, 0.40f, 0.30f, 0.73f, 0.32f, 0.27f, 0.32f,
    0.86f, 0.28f, 0.14f, 0.28f, 0.57f, 0.57f, 0.43f, 0.57f, 0.68f, 0.72f, 0.32f, 0.72f,
    0.70f, 0.94f, 0.30f, 0.94f
)

/** Mirrors a pose left-to-right (and swaps the dancer's left/right joints). */
private fun mirror(pose: FloatArray): FloatArray {
    val out = FloatArray(pose.size)
    // Joint 0 (head) and 1 (neck) stay; the rest come in L/R pairs.
    fun copyJoint(from: Int, to: Int) {
        out[to * 2] = 1f - pose[from * 2]
        out[to * 2 + 1] = pose[from * 2 + 1]
    }
    copyJoint(0, 0)
    copyJoint(1, 1)
    var j = 2
    while (j < pose.size / 2) {
        copyJoint(j, j + 1)
        copyJoint(j + 1, j)
        j += 2
    }
    return out
}

private val KEYFRAMES = listOf(POSE_ARMS_UP, POSE_WAVE, POSE_WIDE, mirror(POSE_WAVE))

/** Bones as joint index pairs. */
private val BONES = listOf(
    1 to 2, 1 to 3, 2 to 4, 4 to 6, 3 to 5, 5 to 7, // arms
    1 to 8, 1 to 9, 8 to 9,                           // torso
    8 to 10, 10 to 12, 9 to 11, 11 to 13              // legs
)

/**
 * A pose-skeleton dancer looping through a short routine, drawn in the brand
 * gradient with a soft glow, the same skeleton style the practice screen uses.
 */
@Composable
fun DancingFigure(modifier: Modifier = Modifier) {
    val colors = ChorevoTheme.colors
    val transition = rememberInfiniteTransition(label = "dancer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = KEYFRAMES.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(KEYFRAMES.size * 850, easing = LinearEasing), RepeatMode.Restart),
        label = "dancerPhase"
    )

    Canvas(modifier = modifier.testTag("dancingFigure")) {
        val index = phase.toInt() % KEYFRAMES.size
        val next = (index + 1) % KEYFRAMES.size
        val t = phase - phase.toInt()
        val eased = t * t * (3f - 2f * t) // smoothstep
        val a = KEYFRAMES[index]
        val b = KEYFRAMES[next]
        val pose = FloatArray(a.size) { i -> a[i] + (b[i] - a[i]) * eased }
        drawSkeleton(pose, colors.gradientStart, colors.gradientEnd)
    }
}

private fun DrawScope.drawSkeleton(pose: FloatArray, start: Color, end: Color) {
    // Keep a 0.6 aspect figure centred in the canvas.
    val figH = size.height
    val figW = figH * 0.6f
    val left = (size.width - figW) / 2f
    fun joint(i: Int) = Offset(left + pose[i * 2] * figW, pose[i * 2 + 1] * figH)

    val brush = Brush.linearGradient(listOf(start, end), start = Offset(left, 0f), end = Offset(left + figW, figH))
    val stroke = figW * 0.055f

    // Glow pass, then the bones.
    BONES.forEach { (i, j) ->
        drawLine(brush, joint(i), joint(j), strokeWidth = stroke * 3f, cap = StrokeCap.Round, alpha = 0.18f)
    }
    BONES.forEach { (i, j) ->
        drawLine(brush, joint(i), joint(j), strokeWidth = stroke, cap = StrokeCap.Round)
    }
    for (i in 1 until pose.size / 2) {
        drawCircle(Color.White, radius = stroke * 0.55f, center = joint(i))
    }
    drawCircle(brush, radius = figW * 0.1f, center = joint(0))
}

/**
 * Dark stage background with two slowly drifting brand-colour glows; content
 * is drawn on top.
 */
@Composable
fun StageBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = ChorevoTheme.colors
    val base = Color(0xFF0B0B14)
    val transition = rememberInfiniteTransition(label = "stage")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart),
        label = "stageDrift"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(base)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * 0.75f
            val c1 = Offset(
                size.width * (0.2f + 0.1f * cos(drift)),
                size.height * (0.25f + 0.08f * sin(drift))
            )
            val c2 = Offset(
                size.width * (0.85f - 0.1f * sin(drift)),
                size.height * (0.55f + 0.08f * cos(drift))
            )
            drawCircle(
                Brush.radialGradient(listOf(colors.gradientStart.copy(alpha = 0.35f), Color.Transparent), c1, r),
                radius = r,
                center = c1
            )
            drawCircle(
                Brush.radialGradient(listOf(colors.gradientEnd.copy(alpha = 0.3f), Color.Transparent), c2, r),
                radius = r,
                center = c2
            )
        }
        content()
    }
}
