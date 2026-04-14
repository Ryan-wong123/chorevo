package com.example.chorevo.danceactivity

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chorevo.core.Ids
import com.example.chorevo.core.CameraPreview
import com.example.chorevo.posedetection.ImageProxyUtils
import com.example.chorevo.posedetection.PoseDrawSpec
import com.example.chorevo.posedetection.PoseLandmarkerHelper
import com.example.chorevo.posedetection.PoseOverlay
import com.example.chorevo.posedetection.PoseSimilarityEvaluator
import com.example.chorevo.posedetection.RenderMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

import com.example.chorevo.analytics.TimelinePoint
import com.example.chorevo.analytics.PerformanceSegment
import com.example.chorevo.analytics.SegmentLabel
import com.example.chorevo.analytics.SessionAnalysisPayload
import com.example.chorevo.core.CameraBinder
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel

import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.example.chorevo.danceactivity.ViewModel.AchievementViewModel

/** Displays the dance ar practice screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanceARPracticeScreen(
    item: DanceLibraryModel,
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    vm: DanceLibraryListViewModel,
    achievementVm: AchievementViewModel,
    uid: String,
    testMode: Boolean = false
){
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val uiScope = rememberCoroutineScope()

    var tsStart by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var practiceTimeMs by remember { mutableLongStateOf(0L) }

    var hasCamPermission by remember { mutableStateOf(testMode) }

    val camPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            hasCamPermission = it
        }

    val uploadState by vm.uploadState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    //Achievement View Model
    val achievementState by achievementVm.uiState.collectAsStateWithLifecycle()

    //Snackbar handlign for achievement errors
    LaunchedEffect(achievementState.errorMessage) {
        val msg = achievementState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        achievementVm.clearError()
    }


    // Only show snackbar for genuine errors — NOT for success messages.
    // Navigation is owned exclusively by the achievement dialog's onDismiss,
    // so we must NOT call onBack() from any LaunchedEffect here.
    LaunchedEffect(uploadState.message) {
        val msg = uploadState.message ?: return@LaunchedEffect
        if (msg != "Submitted practice result") {
            snackbarHostState.showSnackbar(msg)
        }
        vm.clearUploadMessage()
    }

    LaunchedEffect(Unit) {
        if (!testMode) {
            camPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(tsStart) {
        while (true) {
            practiceTimeMs = System.currentTimeMillis() - tsStart
            delay(33)
        }
    }

    val poseHelper = remember(hasCamPermission, testMode) {
        if (hasCamPermission && !testMode) PoseLandmarkerHelper(context) else null
    }

    DisposableEffect(poseHelper) {
        onDispose { poseHelper?.close() }
    }

    DisposableEffect(Unit) {
        onDispose {
            CameraBinder.unbind()
        }
    }

    var learnerLandmarks by remember { mutableStateOf<List<Float>?>(null) }

    val template = item.template

    val similarityEval = remember(template, testMode) {
        if (testMode) {
            null
        } else {
            template?.frames?.takeIf { it.isNotEmpty() }?.let {
                PoseSimilarityEvaluator(template = template, mirrorReferenceX = true)
            }
        }
    }

    var refLandmarksForCamera by remember { mutableStateOf<List<Float>?>(null) }

    var alignmentScore by remember { mutableDoubleStateOf(if (testMode) 0.82 else 0.0) }
    var timingConsistencyScore by remember { mutableDoubleStateOf(if (testMode) 0.91 else 1.0) }
    var smoothnessScore by remember { mutableDoubleStateOf(if (testMode) 0.88 else 1.0) }
    var avgPoseError by remember { mutableDoubleStateOf(if (testMode) 0.1234 else 0.0) }
    var frameCount by remember { mutableIntStateOf(if (testMode) 42 else 0) }

    var latestTimelinePoints by remember { mutableStateOf<List<TimelinePoint>>(emptyList()) }
    var latestSegments by remember { mutableStateOf<List<PerformanceSegment>>(emptyList()) }

    var status by remember { mutableStateOf<String?>(if (testMode) "Test Mode" else null) }

    /** Handles mirror x. */
    fun mirrorX(flat: List<Float>): List<Float> {
        if (flat.isEmpty()) return flat
        val out = flat.toMutableList()
        val n = out.size / 4
        for (i in 0 until n) {
            val base = i * 4
            out[base] = 1f - out[base]
        }
        return out
    }

    LaunchedEffect(template, practiceTimeMs, testMode) {
        if (testMode) {
            refLandmarksForCamera = null
            return@LaunchedEffect
        }

        val frames = template?.frames ?: run {
            refLandmarksForCamera = null
            return@LaunchedEffect
        }

        if (frames.isEmpty()) {
            refLandmarksForCamera = null
            return@LaunchedEffect
        }

        val ref = frames.minByOrNull { abs(it.tMs - practiceTimeMs) }?.landmarks
        refLandmarksForCamera = ref?.let { mirrorX(it) }
    }

    val analyzer = remember(similarityEval, poseHelper, hasCamPermission, testMode) {
        ImageAnalysis.Analyzer { imageProxy ->
            if (testMode) {
                imageProxy.close()
                return@Analyzer
            }

            if (!hasCamPermission) {
                imageProxy.close()
                return@Analyzer
            }

            val helper = poseHelper
            if (helper == null) {
                imageProxy.close()
                return@Analyzer
            }

            try {
                val bmp = imageProxy.toBitmapFrontCamera()
                val res = helper.detect(bmp)
                val pose = res.landmarks().firstOrNull()

                if (!pose.isNullOrEmpty()) {
                    val flat = ArrayList<Float>(pose.size * 4)
                    for (lm in pose) {
                        flat.add(lm.x())
                        flat.add(lm.y())
                        flat.add(lm.z())
                        flat.add(lm.visibility().orElse(1.0f))
                    }

                    val ev = similarityEval
                    val snap = if (ev != null) {
                        ev.onFrame(flat, practiceTimeMs)
                        ev.snapshot(includeSeries = false)
                    } else null

                    uiScope.launch {
                        learnerLandmarks = flat

                        if (snap != null) {
                            alignmentScore = snap.alignmentScore
                            timingConsistencyScore = snap.timingConsistencyScore
                            smoothnessScore = snap.smoothnessScore
                            avgPoseError = snap.avgPoseError
                            frameCount = snap.frameCount
                        } else {
                            frameCount += 1
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                imageProxy.close()
            }
        }
    }

    /** Performs reset. */
    fun doReset() {
        tsStart = System.currentTimeMillis()
        practiceTimeMs = 0L

        alignmentScore = if (testMode) 0.82 else 0.0
        timingConsistencyScore = if (testMode) 0.91 else 1.0
        smoothnessScore = if (testMode) 0.88 else 1.0
        avgPoseError = if (testMode) 0.1234 else 0.0
        frameCount = if (testMode) 42 else 0
        latestTimelinePoints = emptyList()
        latestSegments = emptyList()

        status = "Reset"
    }

    /** Performs submit. */
    fun doSubmit() {
        val tsEnd = System.currentTimeMillis()
        val durationMs = tsEnd - tsStart
        val ev = similarityEval

        if (ev != null) {
            val full = ev.snapshot(includeSeries = true)
            latestTimelinePoints = full.timelinePoints
            latestSegments = full.performanceSegments
        }

        val payloadTimeline = latestTimelinePoints.map {
            TimelinePoint(
                tMs = it.tMs,
                score = it.score,
                err = it.err
            )
        }

        val payloadSegments = latestSegments.map {
            PerformanceSegment(
                startMs = it.startMs,
                endMs = it.endMs,
                label = SegmentLabel.valueOf(it.label.name),
                avgScore = it.avgScore,
                avgErr = it.avgErr
            )
        }

        val analysisPayload = SessionAnalysisPayload(
            danceId = item.danceId,
            alignmentScore = alignmentScore,
            timingConsistencyScore = timingConsistencyScore,
            smoothnessScore = smoothnessScore,
            avgPoseError = avgPoseError,
            frames = frameCount,
            educatorUid = item.educatorUid,
            videoUrl = item.videoUrl,
            videoPositionMs = practiceTimeMs,
            timelinePoints = payloadTimeline,
            performanceSegments = payloadSegments
        )

        val session = DanceSessionEntity(
            sessionId = Ids.newSessionId(),
            tsStart = tsStart,
            tsEnd = tsEnd,
            activityName = item.title,
            activityType = "LIVE_CAMERA_TEMPLATE",
            durationMs = durationMs,
            smoothnessScore = smoothnessScore,
            balanceScore = 1.0,
            timingConsistencyScore = timingConsistencyScore,
            alignmentScore = alignmentScore,
            qualityFlag = if (alignmentScore >= 0.75) "GOOD" else "DONE",
            analysisJson = analysisPayload.toJson()
        )

        status = "Submitting practice result…"

        scope.launch {
            try {
                vm.saveSessionAndUpload(
                    session = session,
                    extraAnalytics = emptyMap(),
                    onSuccess = { },
                    uid = uid
                )

                achievementVm.processCompletedSession(
                    uid = uid,
                    alignmentScore = alignmentScore,
                    timingConsistencyScore = timingConsistencyScore,
                    smoothnessScore = smoothnessScore
                )

                status = null
            } catch (e: Exception) {
                status = "Error: ${e.message ?: "Something went wrong"}"
            }
        }
    }

    val brandTeal = Color(0xFF00B8C8)
    val tealButtonColors = ButtonDefaults.buttonColors(
        containerColor = brandTeal,
        contentColor = Color.White,
        disabledContainerColor = brandTeal.copy(alpha = 0.5f),
        disabledContentColor = Color.White.copy(alpha = 0.85f)
    )
    val tealOutlinedButtonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = brandTeal
    )

    // ── Achievement Dialog ────────────────────────────────────────────────────
    if (achievementState.showAchievementDialog) {
        AchievementSummaryDialog(
            sessionPoints = achievementState.sessionPoints,
            totalPoints = achievementState.totalPoints,
            currentLevel = achievementState.currentLevel,
            leveledUp = achievementState.leveledUp,
            previousLevel = achievementState.previousLevel,
            newBadges = achievementState.newlyUnlocked,
            brandTeal = brandTeal,
            onDismiss = {
                achievementVm.dismissAchievementDialog()
                onNavigateToProfile()
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("AR Practice: ${item.title}") },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        colors = ButtonDefaults.textButtonColors(contentColor = brandTeal)
                    ) {
                        Text("Back")
                    }
                }
            )
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            if (!hasCamPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Camera permission required.")
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { camPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = tealButtonColors
                    ) {
                        Text("Grant Permission")
                    }
                }
                return@Box
            }

            if (!testMode) {
                CameraPreview(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    analyzer = analyzer,
                    modifier = Modifier.fillMaxSize()
                )

                PoseOverlay(
                    modifier = Modifier.fillMaxSize(),
                    poses = listOfNotNull(
                        refLandmarksForCamera?.let {
                            PoseDrawSpec(
                                landmarks = it,
                                color = Color.Cyan,
                                alpha = 0.35f,
                                jointRadius = 6f,
                                boneWidth = 5f,
                                renderMode = RenderMode.AVATAR
                            )
                        },
                        learnerLandmarks?.let {
                            PoseDrawSpec(
                                landmarks = it,
                                color = Color.Green,
                                alpha = 0.95f,
                                jointRadius = 7f,
                                boneWidth = 6f
                            )
                        }
                    )
                )
            }

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth(0.92f),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Live Analytics",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Align ${(alignmentScore * 100).toInt()}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Timing ${(timingConsistencyScore * 100).toInt()}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Smooth ${(smoothnessScore * 100).toInt()}",
                            style = MaterialTheme.typography.bodySmall)
                    }

                    Text(
                        text = "Err ${"%.4f".format(avgPoseError)} • Frames $frameCount • ${practiceTimeMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { doReset() },
                            contentPadding = PaddingValues(vertical = 8.dp),
                            colors = tealOutlinedButtonColors
                        ) {
                            Text("Reset", style = MaterialTheme.typography.bodyMedium)
                        }

                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { doSubmit() },
                            contentPadding = PaddingValues(vertical = 8.dp),
                            colors = tealButtonColors
                        ) {
                            Text("Submit", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

// ── Achievement Summary Dialog ────────────────────────────────────────────────
@Composable
private fun AchievementSummaryDialog(
    sessionPoints: Int,
    totalPoints: Int,
    currentLevel: AchievementLevel,
    leveledUp: Boolean,
    previousLevel: AchievementLevel,
    newBadges: List<BadgeDefinition>,
    brandTeal: Color,
    onDismiss: () -> Unit
) {
    val nextLevel = nextLevelForPoints(totalPoints)
    val progressToNext = if (nextLevel != null) {
        val range = nextLevel.pointsRequired - currentLevel.pointsRequired
        val soFar = totalPoints - currentLevel.pointsRequired
        (soFar.toFloat() / range.toFloat()).coerceIn(0f, 1f)
    } else 1f

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                if (leveledUp) {
                    Text("🎉 Level Up!", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${previousLevel.emoji} ${previousLevel.title}  →  " +
                                "${currentLevel.emoji} ${currentLevel.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = brandTeal,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        "${currentLevel.emoji} ${currentLevel.title}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider()

                // Points earned this session
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "+$sessionPoints pts",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = brandTeal
                    )
                    Text(
                        "this session",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$totalPoints total points",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Progress bar to next level
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            currentLevel.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            nextLevel?.title ?: "Max Level",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progressToNext },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = brandTeal,
                        trackColor = brandTeal.copy(alpha = 0.2f)
                    )
                    if (nextLevel != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${nextLevel.pointsRequired - totalPoints} pts to ${nextLevel.title}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }

                // New badges
                if (newBadges.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "🏅 New Badges Unlocked!",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        newBadges.forEach { badge ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = brandTeal.copy(alpha = 0.08f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(badge.emoji, fontSize = 24.sp)
                                Column {
                                    Text(
                                        badge.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        badge.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Dismiss
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brandTeal,
                        contentColor = Color.White
                    )
                ) {
                    Text("Awesome!", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun ImageProxy.toBitmapFrontCamera(): Bitmap {
    val bmp = ImageProxyUtils.toBitmap(this)
    val rotation = imageInfo.rotationDegrees.toFloat()

    val matrix = Matrix().apply {
        postRotate(rotation)
        postScale(
            -1f,
            1f,
            bmp.width / 2f,
            bmp.height / 2f
        )
    }

    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
}
