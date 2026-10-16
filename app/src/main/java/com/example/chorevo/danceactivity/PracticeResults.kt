package com.example.chorevo.danceactivity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.example.chorevo.core.rememberHaptics
import com.example.chorevo.R
import com.example.chorevo.core.share.ShareLinks
import com.example.chorevo.ui.theme.ChorevoTheme
import com.example.chorevo.ui.theme.PillShape
import java.io.File
import kotlin.random.Random

/** Scores of the session just submitted, shown on the results screen. */
data class PracticeResult(
    val danceId: String,
    val danceTitle: String,
    val alignment: Double,
    val timing: Double,
    val smoothness: Double,
    val isPersonalBest: Boolean
)

/** Letter grade for a session's alignment score. */
internal fun gradeFor(score: Double): String = when {
    score >= 0.90 -> "S"
    score >= 0.80 -> "A"
    score >= 0.65 -> "B"
    else -> "C"
}

/** Remembers each user's best alignment per dance on this device. */
internal class PersonalBestStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("practice_personal_bests", Context.MODE_PRIVATE)

    /** Records [score] and returns true if it beats (or is the first) best for this dance. */
    fun recordIfBest(uid: String, danceId: String, score: Double): Boolean {
        val key = "best_${uid}_$danceId"
        val previous = prefs.getFloat(key, -1f)
        if (score.toFloat() <= previous) return false
        prefs.edit().putFloat(key, score.toFloat()).apply()
        return true
    }
}

/**
 * Full-screen results: animated score count-up, grade badge, confetti on a
 * personal best, sub-scores, points/level/badges, and a share-able result card.
 */
@Composable
internal fun PracticeResultDialog(
    result: PracticeResult?,
    sessionPoints: Int,
    totalPoints: Int,
    currentLevel: AchievementLevel,
    leveledUp: Boolean,
    previousLevel: AchievementLevel,
    newBadges: List<BadgeDefinition>,
    onDismiss: () -> Unit,
    /** The session hasn't synced yet, so the server hasn't awarded its points. */
    rewardsPending: Boolean = false,
    /** The daily limit of rewarded sessions was reached. */
    rewardsCapped: Boolean = false
) {
    val context = LocalContext.current
    val colors = ChorevoTheme.colors
    val score = result?.alignment ?: 0.0
    val grade = gradeFor(score)

    val countUp = remember { Animatable(0f) }
    val gradePop = remember { Animatable(0f) }
    val haptics = rememberHaptics()
    LaunchedEffect(score) {
        countUp.animateTo((score * 100).toFloat(), tween(1200, easing = FastOutSlowInEasing))
        haptics.success()
        gradePop.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
    }

    val nextLevel = nextLevelForPoints(totalPoints)
    val progressToNext = if (nextLevel != null) {
        val range = nextLevel.pointsRequired - currentLevel.pointsRequired
        val soFar = totalPoints - currentLevel.pointsRequired
        (soFar.toFloat() / range.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("practiceResults"),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = if (result?.isPersonalBest == true) "New personal best!" else "Session complete",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (result?.isPersonalBest == true) colors.like else MaterialTheme.colorScheme.onBackground
                    )
                    result?.let {
                        Text(
                            text = it.danceTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Score + grade
                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
                            val inset = stroke.width / 2
                            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                            drawArc(
                                color = Color.Gray.copy(alpha = 0.2f),
                                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                                topLeft = Offset(inset, inset), size = arcSize, style = stroke
                            )
                            drawArc(
                                brush = colors.brandGradient,
                                startAngle = -90f, sweepAngle = 360f * (countUp.value / 100f),
                                useCenter = false,
                                topLeft = Offset(inset, inset), size = arcSize, style = stroke
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${countUp.value.toInt()}",
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.testTag("resultScore")
                            )
                            Text(
                                text = "SCORE",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = 0.5f + gradePop.value * 0.5f
                                scaleY = 0.5f + gradePop.value * 0.5f
                                alpha = gradePop.value
                            }
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(colors.brandGradient)
                            .testTag("resultGrade"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(grade, style = MaterialTheme.typography.headlineMedium, color = colors.onGradient)
                    }

                    result?.let {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatPill("Alignment", it.alignment, Modifier.weight(1f))
                            StatPill("Timing", it.timing, Modifier.weight(1f))
                            StatPill("Smooth", it.smoothness, Modifier.weight(1f))
                        }
                    }

                    // Points and level (awarded by the server once the session syncs)
                    if (rewardsPending) {
                        Text(
                            text = "Your points will be added when this session syncs.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("rewardsPending")
                        )
                    } else {
                        Text(
                            text = "+$sessionPoints pts",
                            style = MaterialTheme.typography.headlineMedium,
                            color = colors.accent
                        )
                        Text(
                            text = if (leveledUp) {
                                "Level up! ${previousLevel.emoji} ${previousLevel.title} → ${currentLevel.emoji} ${currentLevel.title}"
                            } else {
                                "${currentLevel.emoji} ${currentLevel.title} · $totalPoints total points"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                progress = { progressToNext },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(PillShape),
                                color = colors.accent,
                                trackColor = colors.accent.copy(alpha = 0.2f)
                            )
                            if (nextLevel != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${nextLevel.pointsRequired - totalPoints} pts to ${nextLevel.title}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (rewardsCapped) {
                            Text(
                                text = "You've reached today's points limit. Keep practising — points return tomorrow.",
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (newBadges.isNotEmpty()) {
                        Text("New badges unlocked!", style = MaterialTheme.typography.titleSmall)
                        newBadges.forEach { badge ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(colors.accent.copy(alpha = 0.1f))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(badge.emoji, style = MaterialTheme.typography.headlineSmall)
                                Column {
                                    Text(badge.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        badge.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (result != null) {
                        OutlinedButton(
                            onClick = { shareResultCard(context, result, grade) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("shareResultButton"),
                            shape = PillShape
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Share my result")
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        shape = PillShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.onAccent
                        )
                    ) {
                        Text("Awesome!")
                    }
                }
            }

            if (result?.isPersonalBest == true) {
                Confetti(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Falling confetti burst that plays once. */
@Composable
private fun Confetti(modifier: Modifier = Modifier) {
    val palette = listOf(
        ChorevoTheme.colors.gradientStart,
        ChorevoTheme.colors.gradientEnd,
        Color(0xFFFBBF24),
        Color(0xFF34D399),
        Color(0xFFB3A1FF)
    )
    val pieces = remember {
        List(90) {
            ConfettiPiece(
                x = Random.nextFloat(),
                delay = Random.nextFloat() * 0.35f,
                speed = 0.6f + Random.nextFloat() * 0.8f,
                drift = (Random.nextFloat() - 0.5f) * 0.3f,
                spin = Random.nextFloat() * 720f,
                colorIndex = Random.nextInt(5)
            )
        }
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(2800, easing = LinearEasing)) }

    if (progress.value >= 1f) return
    Canvas(modifier = modifier.testTag("confetti")) {
        pieces.forEach { piece ->
            val t = ((progress.value - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach
            val x = (piece.x + piece.drift * t) * size.width
            val y = -20f + t * piece.speed * size.height * 1.2f
            val w = 10.dp.toPx()
            val h = 16.dp.toPx()
            rotate(piece.spin * t, Offset(x, y)) {
                drawRect(
                    color = palette[piece.colorIndex].copy(alpha = 1f - t * 0.6f),
                    topLeft = Offset(x - w / 2, y - h / 2),
                    size = Size(w, h)
                )
            }
        }
    }
}

private data class ConfettiPiece(
    val x: Float,
    val delay: Float,
    val speed: Float,
    val drift: Float,
    val spin: Float,
    val colorIndex: Int
)

/** Draws a branded result card image and opens the system share sheet with it. */
private fun shareResultCard(context: Context, result: PracticeResult, grade: String) {
    runCatching {
        val bitmap = drawResultCard(context, result, grade)
        val dir = File(context.cacheDir, "shared_results").apply { mkdirs() }
        val file = File(dir, "chorevo_result.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val link = ShareLinks.danceLink(result.danceId)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_TEXT,
                "I scored ${(result.alignment * 100).toInt()} ($grade) on \"${result.danceTitle}\" in Chorevo! $link"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share your result").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun drawResultCard(context: Context, result: PracticeResult, grade: String): Bitmap {
    val width = 1080
    val height = 1350
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    val background = Paint().apply {
        shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            0xFF14D8E6.toInt(), 0xFFFF3D8B.toInt(), Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

    val display = runCatching { ResourcesCompat.getFont(context, R.font.sora_bold) }.getOrNull() ?: Typeface.DEFAULT_BOLD
    val body = runCatching { ResourcesCompat.getFont(context, R.font.jakarta_semibold) }.getOrNull() ?: Typeface.DEFAULT

    fun text(value: String, y: Float, sizePx: Float, face: Typeface, alpha: Int = 255) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.alpha = alpha
            textSize = sizePx
            typeface = face
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(value, width / 2f, y, paint)
    }

    text("CHOREVO", 150f, 64f, display, 230)
    val title = if (result.danceTitle.length > 28) result.danceTitle.take(27) + "…" else result.danceTitle
    text(title, 280f, 56f, body)
    text("${(result.alignment * 100).toInt()}", 680f, 360f, display)
    text("SCORE", 770f, 48f, body, 220)

    // Grade disc
    val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; alpha = 60 }
    canvas.drawCircle(width / 2f, 940f, 110f, disc)
    text(grade, 985f, 130f, display)

    if (result.isPersonalBest) text("NEW PERSONAL BEST", 1130f, 52f, body)
    text(
        "Alignment ${(result.alignment * 100).toInt()} · Timing ${(result.timing * 100).toInt()} · Smooth ${(result.smoothness * 100).toInt()}",
        1220f, 38f, body, 230
    )
    return bitmap
}
