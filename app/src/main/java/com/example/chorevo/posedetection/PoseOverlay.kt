package com.example.chorevo.posedetection

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.max

enum class RenderMode {
    SKELETON,
    AVATAR
}

data class PoseDrawSpec(
    val landmarks: List<Float>,
    val color: Color,
    val alpha: Float,
    val jointRadius: Float,
    val boneWidth: Float,
    val visibilityThreshold: Float = 0.3f,

    val renderMode: RenderMode = RenderMode.SKELETON
)

/** Handles pose overlay. */
@Composable
fun PoseOverlay(
    modifier: Modifier,
    poses: List<PoseDrawSpec>
) {
    Canvas(modifier = modifier) {

        /** Handles idx. */
        fun idx(i: Int) = i * 4
        /** Handles visibility. */
        fun visibility(flat: List<Float>, i: Int): Float = flat[idx(i) + 3]

        /** Handles point. */
        fun point(flat: List<Float>, i: Int): Offset {
            val x = flat[idx(i)]
            val y = flat[idx(i) + 1]
            return Offset(x * size.width, y * size.height)
        }

        // MediaPipe Pose connections (same as your current list)
        val bones: List<Pair<Int, Int>> = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 7,
            0 to 4, 4 to 5, 5 to 6, 6 to 8,
            9 to 10,

            11 to 12,
            11 to 23, 12 to 24,
            23 to 24,

            11 to 13, 13 to 15,
            15 to 17, 15 to 19, 15 to 21,
            17 to 19,

            12 to 14, 14 to 16,
            16 to 18, 16 to 20, 16 to 22,
            18 to 20,

            23 to 25, 25 to 27,
            27 to 29, 27 to 31,
            29 to 31,

            24 to 26, 26 to 28,
            28 to 30, 28 to 32,
            30 to 32
        )

        /** Handles draw skeleton. */
        fun drawSkeleton(
            flat: List<Float>,
            color: Color,
            alpha: Float,
            jointRadius: Float,
            boneWidth: Float,
            visibilityThreshold: Float
        ) {
            val n = flat.size / 4
            if (n <= 0) return

            val w = max(2f, boneWidth)

            for ((a, b) in bones) {
                if (a >= n || b >= n) continue
                val va = visibility(flat, a)
                val vb = visibility(flat, b)
                if (va < visibilityThreshold || vb < visibilityThreshold) continue

                drawLine(
                    color = color.copy(alpha = alpha),
                    start = point(flat, a),
                    end = point(flat, b),
                    strokeWidth = w,
                    cap = StrokeCap.Round
                )
            }

            for (i in 0 until n) {
                val vis = visibility(flat, i)
                if (vis < visibilityThreshold) continue

                drawCircle(
                    color = color,
                    radius = jointRadius,
                    center = point(flat, i),
                    alpha = alpha
                )
            }
        }

        /** Handles draw avatar like. */
        fun drawAvatarLike(
            flat: List<Float>,
            baseColor: Color,
            alpha: Float,
            boneWidth: Float,
            visibilityThreshold: Float
        ) {
            val n = flat.size / 4
            if (n <= 0) return

            // safe point or null
            fun p(i: Int): Offset? {
                if (i >= n) return null
                if (visibility(flat, i) < visibilityThreshold) return null
                return point(flat, i)
            }

            // Key points
            val shL = p(11); val shR = p(12)
            val hipL = p(23); val hipR = p(24)
            val elL = p(13); val elR = p(14)
            val wrL = p(15); val wrR = p(16)
            val knL = p(25); val knR = p(26)
            val anL = p(27); val anR = p(28)

            // Thickness scaling (so it looks consistent across screens)
            val thickness = max(8f, boneWidth * 2.4f)
            val limbStroke = Stroke(width = thickness, cap = StrokeCap.Round)

            // Slight gradient to feel more "body-like"
            val bodyBrush = Brush.verticalGradient(
                colors = listOf(
                    baseColor.copy(alpha = alpha * 0.55f),
                    baseColor.copy(alpha = alpha * 0.20f)
                )
            )

            // 1) Torso polygon (shoulders + hips)
            if (shL != null && shR != null && hipL != null && hipR != null) {
                val torso = Path().apply {
                    moveTo(shL.x, shL.y)
                    lineTo(shR.x, shR.y)
                    lineTo(hipR.x, hipR.y)
                    lineTo(hipL.x, hipL.y)
                    close()
                }
                drawPath(path = torso, brush = bodyBrush)
            }

            // 2) Upper legs fill (hips -> knees)
            fun quadFill(a: Offset, b: Offset, c: Offset, d: Offset) {
                val path = Path().apply {
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
                }
                drawPath(path, brush = bodyBrush)
            }

            if (hipL != null && hipR != null && knL != null && knR != null) {
                quadFill(hipL, hipR, knR, knL)
            }

            // 3) Limb tubes (rounded thick lines)
            fun tube(a: Offset?, b: Offset?) {
                if (a == null || b == null) return
                drawLine(
                    color = baseColor.copy(alpha = alpha * 0.85f),
                    start = a,
                    end = b,
                    strokeWidth = thickness,
                    cap = StrokeCap.Round
                )
            }

            // Arms
            tube(shL, elL); tube(elL, wrL)
            tube(shR, elR); tube(elR, wrR)

            // Legs
            tube(hipL, knL); tube(knL, anL)
            tube(hipR, knR); tube(knR, anR)

            // Spine-ish line (center shoulders -> center hips)
            if (shL != null && shR != null && hipL != null && hipR != null) {
                val shC = Offset((shL.x + shR.x) / 2f, (shL.y + shR.y) / 2f)
                val hipC = Offset((hipL.x + hipR.x) / 2f, (hipL.y + hipR.y) / 2f)
                drawLine(
                    color = baseColor.copy(alpha = alpha * 0.55f),
                    start = shC,
                    end = hipC,
                    strokeWidth = max(6f, thickness * 0.6f),
                    cap = StrokeCap.Round
                )
            }

            // 4) Head glow (use landmark 0 if visible)
            val head = p(0)
            if (head != null) {
                drawCircle(
                    color = baseColor.copy(alpha = alpha * 0.45f),
                    radius = max(10f, thickness * 0.9f),
                    center = head
                )
            }
        }

        // draw all poses in order (ref first, live last)
        for (spec in poses) {
            when (spec.renderMode) {
                RenderMode.SKELETON -> drawSkeleton(
                    flat = spec.landmarks,
                    color = spec.color,
                    alpha = spec.alpha,
                    jointRadius = spec.jointRadius,
                    boneWidth = spec.boneWidth,
                    visibilityThreshold = spec.visibilityThreshold
                )
                RenderMode.AVATAR -> drawAvatarLike(
                    flat = spec.landmarks,
                    baseColor = spec.color,
                    alpha = spec.alpha,
                    boneWidth = spec.boneWidth,
                    visibilityThreshold = spec.visibilityThreshold
                )
            }
        }
    }
}
