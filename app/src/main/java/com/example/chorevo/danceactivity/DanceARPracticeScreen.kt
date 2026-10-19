package com.example.chorevo.danceactivity

import androidx.activity.compose.LocalActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.chorevo.danceactivity.ViewModel.PracticeSubmission
import com.example.chorevo.danceactivity.ViewModel.PracticeViewModel
import com.example.chorevo.core.rememberHaptics
import com.example.chorevo.ui.theme.PillShape
import com.example.chorevo.ui.theme.ChorevoTheme
import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.chorevo.core.CameraBinder
import com.example.chorevo.core.CameraPreview
import com.example.chorevo.core.SharedPlayerView
import com.example.chorevo.danceactivity.ViewModel.AchievementViewModel
import com.example.chorevo.weeklychallenge.ViewModel.WeeklyChallengeViewModel
import com.example.chorevo.weeklychallenge.WeeklyChallengeCelebrationDialog
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.posedetection.ImageProxyUtils
import com.example.chorevo.posedetection.InstructorAvatarOverlay
import com.example.chorevo.posedetection.PoseDrawSpec
import com.example.chorevo.posedetection.PoseOverlay
import com.example.chorevo.posedetection.PoseSelectionPreview
import com.example.chorevo.posedetection.PoseTrack
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Displays the dance practice screen. State and logic live in [PracticeViewModel]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanceARPracticeScreen(
    item: DanceLibraryModel,
    onBack: () -> Unit,
    onNavigateToProfile: () -> Unit,
    vm: DanceLibraryListViewModel,
    achievementVm: AchievementViewModel,
    weeklyChallengeVm: WeeklyChallengeViewModel,
    uid: String,
    testMode: Boolean = false,
    practiceVm: PracticeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalActivity.current

    remember(item.danceId, testMode) {
        practiceVm.bind(item, testMode)
    }
    DisposableEffect(practiceVm) {
        onDispose {
            // Keep the session through a rotation; end it when the screen really closes.
            if (activity?.isChangingConfigurations != true) practiceVm.release()
        }
    }

    val state by practiceVm.uiState.collectAsStateWithLifecycle()
    val hasCamPermission = state.hasCamPermission
    val isPracticeActive = state.isPracticeActive
    val isCountdownActive = state.isCountdownActive
    val isPracticeFocused = isPracticeActive && !testMode
    val practiceMode = state.practiceMode
    val practiceSegments = state.practiceSegments
    val currentStepIndex = state.currentStepIndex
    val currentPracticeSegment = state.currentPracticeSegment
    val availableReferenceTracks = state.availableReferenceTracks
    val selectedReferenceTrack = state.selectedReferenceTrack
    val handScore = state.handScore

    // Buzz when a combo starts and again at every 5-second milestone.
    val haptics = rememberHaptics()
    val comboTier = if (state.comboSeconds >= COMBO_MIN_SECONDS) 1 + state.comboSeconds / 5 else 0
    LaunchedEffect(comboTier) {
        if (comboTier > 0) haptics.success()
    }

    val segmentLoopPlayer = if (!testMode) {
        remember(context) {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false
                volume = 0f
            }
        }
    } else {
        null
    }

    DisposableEffect(segmentLoopPlayer) {
        onDispose { segmentLoopPlayer?.release() }
    }

    LaunchedEffect(
        segmentLoopPlayer,
        item.videoUrl,
        currentPracticeSegment,
        practiceMode,
        isPracticeFocused,
        isCountdownActive
    ) {
        val player = segmentLoopPlayer ?: return@LaunchedEffect
        val segment = currentPracticeSegment
        if (
            item.videoUrl.isBlank() ||
            segment == null ||
            practiceMode != PracticeMode.STEP_BY_STEP ||
            isPracticeFocused ||
            isCountdownActive
        ) {
            player.pause()
            return@LaunchedEffect
        }

        val mediaItem = MediaItem.Builder()
            .setUri(item.videoUrl.toUri())
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(segment.startMs)
                    .setEndPositionMs(segment.endMs.coerceAtLeast(segment.startMs + 1L))
                    .build()
            )
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    val camPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            practiceVm.onCameraPermissionResult(granted)
        }

    val uploadState by vm.uploadState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val achievementState by achievementVm.uiState.collectAsStateWithLifecycle()
    val weeklyChallengeState by weeklyChallengeVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(achievementState.errorMessage) {
        val msg = achievementState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        achievementVm.clearError()
    }

    LaunchedEffect(uploadState.message) {
        val msg = uploadState.message ?: return@LaunchedEffect
        if (msg != "Submitted practice result") {
            snackbarHostState.showSnackbar(msg)
        }
        vm.clearUploadMessage()
    }

    LaunchedEffect(item.danceId) {
        if (!testMode) {
            camPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Runs on the camera analysis thread (see CameraBinder).
    val analyzer = remember(practiceVm) {
        ImageAnalysis.Analyzer { imageProxy ->
            try {
                if (practiceVm.acceptsFrames) {
                    practiceVm.onCameraFrame(imageProxy.toBitmapFrontCamera())
                }
            } catch (_: Exception) {
            } finally {
                imageProxy.close()
            }
        }
    }

    /** Saves a finished attempt; points and badges come from the server once it syncs. */
    fun handleSubmission(submission: PracticeSubmission?) {
        submission ?: return
        try {
            vm.saveSessionAndUpload(
                session = submission.session,
                extraAnalytics = emptyMap(),
                onSuccess = { },
                uid = uid
            )
            achievementVm.awaitSessionRewards(uid = uid, sessionId = submission.session.sessionId) { rewards ->
                weeklyChallengeVm.onSessionRewards(uid, rewards)
            }
            practiceVm.setStatus(null)
        } catch (e: Exception) {
            practiceVm.setStatus("Error: ${e.message ?: "Something went wrong"}")
        }
    }

    val brandTeal = ChorevoTheme.colors.accent
    val tealButtonColors = ButtonDefaults.buttonColors(
        containerColor = brandTeal,
        contentColor = ChorevoTheme.colors.onAccent,
        disabledContainerColor = brandTeal.copy(alpha = 0.5f),
        disabledContentColor = ChorevoTheme.colors.onAccent.copy(alpha = 0.85f)
    )
    val tealOutlinedButtonColors = ButtonDefaults.outlinedButtonColors(
        contentColor = brandTeal
    )

    if (weeklyChallengeState.showCelebrationDialog) {
        WeeklyChallengeCelebrationDialog(
            newlyCompleted = weeklyChallengeState.newlyCompleted,
            bonusNewlyUnlocked = weeklyChallengeState.bonusNewlyUnlocked,
            bonusRewardPoints = weeklyChallengeState.challengeSet.bonusRewardPoints,
            onDismiss = { weeklyChallengeVm.dismissCelebrationDialog() }
        )
    } else if (achievementState.showAchievementDialog) {
        PracticeResultDialog(
            result = state.lastResult,
            sessionPoints = achievementState.sessionPoints,
            totalPoints = achievementState.totalPoints,
            currentLevel = achievementState.currentLevel,
            leveledUp = achievementState.leveledUp,
            previousLevel = achievementState.previousLevel,
            newBadges = achievementState.newlyUnlocked,
            rewardsPending = achievementState.rewardsPending,
            rewardsCapped = achievementState.rewardsCapped,
            onDismiss = {
                achievementVm.dismissAchievementDialog()
                onNavigateToProfile()
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = onBack,
                        colors = ButtonDefaults.textButtonColors(contentColor = brandTeal)
                    ) {
                        Text("Back")
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

                InstructorAvatarOverlay(
                    context = context,
                    landmarks = state.refLandmarksForCamera,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize()
                )

                PoseOverlay(
                    modifier = Modifier.fillMaxSize(),
                    poses = listOfNotNull(
                        state.learnerLandmarks?.let {
                            PoseDrawSpec(
                                landmarks = it,
                                color = liveAccuracyColor(state.liveScore),
                                alpha = 0.95f,
                                jointRadius = 7f,
                                boneWidth = 6f
                            )
                        }
                    )
                )
            }

            FloatingPracticeBackButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                brandTeal = brandTeal,
                onBack = onBack
            )

            if (!isPracticeFocused) {
                val showSelector = state.showReferenceSelector && availableReferenceTracks.size > 1
                val showStepControls = practiceSegments.size > 1

                if (showSelector || showStepControls) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 64.dp, start = 12.dp, end = 12.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (showSelector) {
                            ReferenceDancerSelectorCard(
                                modifier = Modifier
                                    .width(128.dp)
                                    .heightIn(max = 340.dp),
                                videoUrl = item.videoUrl,
                                tracks = availableReferenceTracks,
                                selectionPreview = state.selectionPreview,
                                selectedTrackId = selectedReferenceTrack?.trackId,
                                brandTeal = brandTeal,
                                onTrackSelected = practiceVm::selectReferenceTrack
                            )
                        }

                        if (showStepControls) {
                            StepPracticeCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(max = 340.dp),
                                brandTeal = brandTeal,
                                practiceMode = practiceMode,
                                currentSegment = currentPracticeSegment,
                                canGoPrevious = currentStepIndex > 0,
                                canGoNext = currentStepIndex < practiceSegments.lastIndex,
                                onSelectFullRoutine = practiceVm::selectFullRoutine,
                                onSelectStepByStep = practiceVm::selectStepByStep,
                                onPreviousStep = practiceVm::previousStep,
                                onNextStep = practiceVm::nextStep,
                                loopPlayer = segmentLoopPlayer
                            )
                        }
                    }
                }
            }

            // ── Live score ring + combo (top-right) ───────────────────────────
            if (!isCountdownActive && (isPracticeActive || testMode)) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val ringScore = if (handScore != null) state.combinedPracticeScore else (state.liveScore ?: state.alignmentScore)
                    LiveScoreRing(score = ringScore)
                    AnimatedVisibility(
                        visible = state.comboSeconds >= COMBO_MIN_SECONDS,
                        enter = scaleIn() + fadeIn(),
                        exit = fadeOut()
                    ) {
                        ComboBadge(seconds = state.comboSeconds)
                    }
                }
            }

            // ── 3-2-1 countdown ───────────────────────────────────────────────
            if (isCountdownActive) {
                PracticeCountdown(
                    secondsRemaining = state.countdownSecondsRemaining ?: 0,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("practiceStartCountdown")
                )
            }

            // ── Frosted-glass controls (fade while dancing) ───────────────────
            val chorevoColors = ChorevoTheme.colors
            val controlsAlpha by animateFloatAsState(
                targetValue = if (isPracticeFocused) 0.55f else 1f,
                animationSpec = tween(400),
                label = "practiceControlsAlpha"
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth(0.94f)
                    .heightIn(max = if (isPracticeFocused) 96.dp else 248.dp)
                    .graphicsLayer { alpha = controlsAlpha },
                shape = MaterialTheme.shapes.extraLarge,
                color = chorevoColors.glass,
                border = BorderStroke(1.dp, chorevoColors.glassBorder)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isPracticeFocused) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (practiceMode == PracticeMode.STEP_BY_STEP) "Step Analytics" else "Live Analytics",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            selectedReferenceTrack?.takeIf { availableReferenceTracks.size > 1 }?.let { track ->
                                Text(
                                    text = track.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = brandTeal
                                )
                            }
                        }

                        currentPracticeSegment?.let { segment ->
                            Text(
                                text = "${segment.title} • ${formatPracticeTime(segment.startMs)} to ${formatPracticeTime(segment.endMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = brandTeal
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatPill("Align", state.alignmentScore, Modifier.weight(1f))
                            StatPill("Timing", state.timingConsistencyScore, Modifier.weight(1f))
                            StatPill("Smooth", state.smoothnessScore, Modifier.weight(1f))
                            handScore?.let { score -> StatPill("Hands", score, Modifier.weight(1f)) }
                        }

                        state.wordFeedback?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleSmall,
                                color = brandTeal,
                                modifier = Modifier.testTag("wordFeedback")
                            )
                        }

                        state.handFeedback?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = brandTeal,
                                modifier = Modifier.testTag("handFeedback")
                            )
                        }

                        Text(
                            text = "Err ${"%.4f".format(state.avgPoseError)} • Frames ${state.frameCount} • ${state.practiceTimeMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        state.status?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isPracticeActive) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { practiceVm.reset(message = "Practice reset. Tap Start Practice when ready.") },
                                contentPadding = PaddingValues(vertical = 10.dp),
                                shape = PillShape,
                                colors = tealOutlinedButtonColors
                            ) {
                                Text("Reset", style = MaterialTheme.typography.bodyMedium)
                            }

                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { handleSubmission(practiceVm.onFinishAction(uid)) },
                                contentPadding = PaddingValues(vertical = 10.dp),
                                shape = PillShape,
                                colors = tealButtonColors
                            ) {
                                Text(
                                    if (practiceMode == PracticeMode.STEP_BY_STEP) "Finish Step" else "Submit",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = { practiceVm.startPracticeCountdown() },
                                enabled = !isCountdownActive,
                                contentPadding = PaddingValues(vertical = 10.dp),
                                shape = PillShape,
                                colors = tealButtonColors
                            ) {
                                Text(
                                    if (isCountdownActive) "Starting..." else "Start Practice",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { handleSubmission(practiceVm.onSecondaryAction(uid)) },
                                enabled = if (practiceMode == PracticeMode.STEP_BY_STEP) {
                                    practiceSegments.isNotEmpty()
                                } else {
                                    state.frameCount > 0
                                },
                                contentPadding = PaddingValues(vertical = 10.dp),
                                shape = PillShape,
                                colors = tealOutlinedButtonColors
                            ) {
                                Text(
                                    when {
                                        practiceMode != PracticeMode.STEP_BY_STEP -> "Submit"
                                        currentStepIndex < practiceSegments.lastIndex -> "Next Step"
                                        else -> "Restart Step"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingPracticeBackButton(
    modifier: Modifier = Modifier,
    brandTeal: Color,
    onBack: () -> Unit
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
        )
    ) {
        TextButton(
            onClick = onBack,
            colors = ButtonDefaults.textButtonColors(contentColor = brandTeal),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("Back", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StepPracticeCard(
    modifier: Modifier = Modifier,
    brandTeal: Color,
    practiceMode: PracticeMode,
    currentSegment: PracticeStepSegment?,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onSelectFullRoutine: () -> Unit,
    onSelectStepByStep: () -> Unit,
    onPreviousStep: () -> Unit,
    onNextStep: () -> Unit,
    loopPlayer: ExoPlayer?
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("stepPracticeCard"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Practice Mode",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onSelectFullRoutine,
                    enabled = practiceMode != PracticeMode.FULL_ROUTINE,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = brandTeal)
                ) {
                    Text("Full Routine")
                }

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onSelectStepByStep,
                    enabled = practiceMode != PracticeMode.STEP_BY_STEP,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brandTeal,
                        contentColor = ChorevoTheme.colors.onAccent
                    )
                ) {
                    Text("Step-by-Step")
                }
            }

            currentSegment?.let { segment ->
                Text(
                    text = "${segment.title} • ${formatPracticeTime(segment.startMs)} to ${formatPracticeTime(segment.endMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )

                Text(
                    text = "Loop Preview",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = brandTeal
                )

                if (loopPlayer != null) {
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = Color.Black)
                    ) {
                        SharedPlayerView(
                            exoPlayer = loopPlayer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            useController = true
                        )
                    }
                } else {
                    Text(
                        text = "Selected segment is ready to loop.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onPreviousStep,
                        enabled = canGoPrevious,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = brandTeal)
                    ) {
                        Text("Previous")
                    }

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onNextStep,
                        enabled = canGoNext,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = brandTeal)
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

private fun formatPracticeTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun ReferenceDancerSelectorCard(
    modifier: Modifier = Modifier,
    videoUrl: String,
    tracks: List<PoseTrack>,
    selectionPreview: PoseSelectionPreview?,
    selectedTrackId: String?,
    brandTeal: Color,
    onTrackSelected: (String) -> Unit
) {
    val thumbnails by produceState<Map<String, Bitmap>>(
        initialValue = emptyMap(),
        videoUrl,
        selectionPreview
    ) {
        value = loadReferenceTrackThumbnails(videoUrl, selectionPreview)
    }

    Card(
        modifier = modifier.testTag("dancerSelectorCard"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        )
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Reference",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            tracks.forEach { track ->
                val isSelected = track.trackId == selectedTrackId
                val thumbnail = thumbnails[track.trackId]
                if (isSelected) {
                    Button(
                        onClick = { onTrackSelected(track.trackId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dancerChip_${track.trackId}"),
                        shape = PillShape,
                        contentPadding = PaddingValues(vertical = 10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = brandTeal,
                            contentColor = ChorevoTheme.colors.onAccent
                        )
                    ) {
                        if (thumbnail != null) {
                            Image(
                                bitmap = thumbnail.asImageBitmap(),
                                contentDescription = track.label,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = track.label,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { onTrackSelected(track.trackId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dancerChip_${track.trackId}"),
                        shape = PillShape,
                        contentPadding = PaddingValues(vertical = 10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        if (thumbnail != null) {
                            Image(
                                bitmap = thumbnail.asImageBitmap(),
                                contentDescription = track.label,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = track.label,
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadReferenceTrackThumbnails(
    videoUrl: String,
    selectionPreview: PoseSelectionPreview?
): Map<String, Bitmap> = withContext(Dispatchers.IO) {
    if (videoUrl.isBlank() || selectionPreview == null || selectionPreview.candidates.isEmpty()) {
        return@withContext emptyMap()
    }

    val retriever = MediaMetadataRetriever()
    return@withContext try {
        retriever.setDataSource(videoUrl, emptyMap())
        val frame = retriever.getFrameAtTime(
            selectionPreview.timeMs * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST
        ) ?: return@withContext emptyMap()

        selectionPreview.candidates.associate { candidate ->
            candidate.trackId to cropReferenceThumbnail(frame, candidate.bounds)
        }
    } catch (_: Exception) {
        emptyMap()
    } finally {
        runCatching { retriever.release() }
    }
}

private fun cropReferenceThumbnail(
    source: Bitmap,
    bounds: com.example.chorevo.posedetection.PoseBounds
): Bitmap {
    val left = (bounds.left * source.width).toInt().coerceIn(0, source.width - 1)
    val top = (bounds.top * source.height).toInt().coerceIn(0, source.height - 1)
    val right = (bounds.right * source.width).toInt().coerceIn(left + 1, source.width)
    val bottom = (bounds.bottom * source.height).toInt().coerceIn(top + 1, source.height)

    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    val targetWidth = 160
    val targetHeight = 220
    return Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
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
