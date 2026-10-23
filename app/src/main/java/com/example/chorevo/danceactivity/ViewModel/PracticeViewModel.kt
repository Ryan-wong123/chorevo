package com.example.chorevo.danceactivity.ViewModel

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.analytics.FeedbackMessage
import com.example.chorevo.analytics.PerformanceSegment
import com.example.chorevo.analytics.SessionAnalysisPayload
import com.example.chorevo.analytics.TimelinePoint
import com.example.chorevo.core.Ids
import com.example.chorevo.danceactivity.DanceSessionEntity
import com.example.chorevo.danceactivity.LIVE_GOOD_SCORE
import com.example.chorevo.danceactivity.PersonalBestStore
import com.example.chorevo.danceactivity.PracticeMode
import com.example.chorevo.danceactivity.PracticeResult
import com.example.chorevo.danceactivity.PracticeStepSegment
import com.example.chorevo.danceactivity.derivePracticeStepSegments
import com.example.chorevo.danceactivity.sliceTemplateForStep
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.posedetection.HandSimilarityEvaluator
import com.example.chorevo.posedetection.HolisticFrameLandmarks
import com.example.chorevo.posedetection.HolisticFrameSelector
import com.example.chorevo.posedetection.HolisticLandmarkerHelper
import com.example.chorevo.posedetection.PoseLandmarkerHelper
import com.example.chorevo.posedetection.PoseSelectionPreview
import com.example.chorevo.posedetection.PoseSimilarityEvaluator
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTrack
import com.example.chorevo.posedetection.poseBounds
import com.example.chorevo.core.Database.di.DefaultDispatcher
import com.example.chorevo.core.Database.di.MainDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

/** Everything the practice screen draws. */
data class PracticeUiState(
    val hasCamPermission: Boolean = false,
    val isPracticeActive: Boolean = false,
    val countdownSecondsRemaining: Int? = null,
    val practiceTimeMs: Long = 0L,

    val learnerLandmarks: List<Float>? = null,
    val learnerLeftHandLandmarks: List<Float> = emptyList(),
    val learnerRightHandLandmarks: List<Float> = emptyList(),
    val refLandmarksForCamera: List<Float>? = null,

    val alignmentScore: Double = 0.0,
    val timingConsistencyScore: Double = 1.0,
    val smoothnessScore: Double = 1.0,
    val handScore: Double? = null,
    val combinedPracticeScore: Double = 0.0,
    val avgPoseError: Double = 0.0,
    val frameCount: Int = 0,
    val wordFeedback: String? = null,
    val handFeedback: String? = null,
    // Live HUD: fast-reacting score and seconds of continuous good matching.
    val liveScore: Double? = null,
    val comboStartMs: Long? = null,
    val comboSeconds: Int = 0,

    val latestTimelinePoints: List<TimelinePoint> = emptyList(),
    val latestSegments: List<PerformanceSegment> = emptyList(),
    val latestFeedbackMessages: List<FeedbackMessage> = emptyList(),
    val status: String? = null,

    val showReferenceSelector: Boolean = true,
    val availableReferenceTracks: List<PoseTrack> = emptyList(),
    val selectionPreview: PoseSelectionPreview? = null,
    val selectedReferenceTrack: PoseTrack? = null,
    val practiceMode: PracticeMode = PracticeMode.FULL_ROUTINE,
    val practiceSegments: List<PracticeStepSegment> = emptyList(),
    val currentStepIndex: Int = 0,

    // Scores of the last submitted session, for the results screen.
    val lastResult: PracticeResult? = null
) {
    val isCountdownActive: Boolean get() = countdownSecondsRemaining != null

    val currentPracticeSegment: PracticeStepSegment?
        get() = if (practiceMode == PracticeMode.STEP_BY_STEP) practiceSegments.getOrNull(currentStepIndex) else null
}

/** A finished full-routine attempt, ready to be saved and uploaded. */
data class PracticeSubmission(
    val session: DanceSessionEntity
)

/**
 * State and logic of one practice screen: the countdown and practice timer,
 * live pose detection and scoring, step-by-step mode and building the
 * session to submit. The screen only draws [uiState] and forwards events.
 *
 * Camera frames arrive on the camera analysis thread through [onCameraFrame];
 * everything else runs on the main thread.
 */
@HiltViewModel
class PracticeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val personalBests: PersonalBestStore,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // Runs the countdown and practice timer; tests pass a dispatcher they control.
    @MainDispatcher private val timerDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val savedState: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    // The dancer, mode and step survive Android killing the app (see AppViewModel,
    // which reopens practice for the same dance).
    private companion object {
        const val KEY_DANCE_ID = "practice.danceId"
        const val KEY_TRACK_ID = "practice.trackId"
        const val KEY_MODE = "practice.mode"
        const val KEY_STEP = "practice.step"
    }

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private var boundDanceId: String? = null
    private var item: DanceLibraryModel? = null
    @Volatile private var testMode = false
    private var template: PoseTemplate? = null
    private var selectedTrackTemplate: PoseTemplate? = null
    @Volatile private var activeTemplate: PoseTemplate? = null

    // Created per attempt; used from the camera thread and the main thread.
    @Volatile private var similarityEval: PoseSimilarityEvaluator? = null
    @Volatile private var handSimilarityEval: HandSimilarityEvaluator? = null
    @Volatile private var detector: LiveLandmarkDetector? = null
    private var detectorJob: Job? = null

    private var tsStart = System.currentTimeMillis()
    private var countdownJob: Job? = null
    private var timerJob: Job? = null

    /** Shows [item]. Calling it again for the same dance keeps the current state. */
    fun bind(item: DanceLibraryModel, testMode: Boolean) {
        if (boundDanceId == item.danceId && this.testMode == testMode) return
        val restoring = savedState.get<String>(KEY_DANCE_ID) == item.danceId
        val savedTrackId = if (restoring) savedState.get<String>(KEY_TRACK_ID) else null
        val savedMode = if (restoring) savedState.get<String>(KEY_MODE) else null
        val savedStep = if (restoring) savedState.get<Int>(KEY_STEP) else null
        release()
        boundDanceId = item.danceId
        this.item = item
        this.testMode = testMode
        template = item.template

        val tracks = template?.availableTracks().orEmpty()
        _uiState.value = resetScores(
            PracticeUiState(
                hasCamPermission = testMode,
                isPracticeActive = testMode,
                status = if (testMode) "Test Mode" else null,
                availableReferenceTracks = tracks,
                selectionPreview = template?.selectionPreview
            )
        )
        selectTrackInternal(savedTrackId ?: template?.resolveTrack(null)?.trackId)
        val segments = _uiState.value.practiceSegments
        if (savedMode == PracticeMode.STEP_BY_STEP.name && segments.size > 1) {
            _uiState.update {
                it.copy(
                    practiceMode = PracticeMode.STEP_BY_STEP,
                    currentStepIndex = (savedStep ?: 0).coerceIn(0, segments.lastIndex)
                )
            }
            updateActiveTemplate()
        }
        // Test mode starts "practising" straight away, as before.
        if (testMode) {
            tsStart = System.currentTimeMillis()
            startTimer()
        }
    }

    /** Ends this practice screen: stops timers and frees the pose models. */
    fun release() {
        countdownJob?.cancel()
        timerJob?.cancel()
        detectorJob?.cancel()
        detectorJob = null
        detector?.close()
        detector = null
        similarityEval = null
        handSimilarityEval = null
        template = null
        selectedTrackTemplate = null
        activeTemplate = null
        item = null
        boundDanceId = null
        listOf(KEY_DANCE_ID, KEY_TRACK_ID, KEY_MODE, KEY_STEP).forEach { savedState.remove<Any>(it) }
        _uiState.value = PracticeUiState()
    }

    override fun onCleared() {
        release()
    }

    /** False when frames would be ignored, so the camera can skip converting them. */
    val acceptsFrames: Boolean get() = !testMode && detector != null

    fun onCameraPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasCamPermission = granted || testMode) }
        if (granted && !testMode && detector == null && detectorJob?.isActive != true) {
            // Loading the MediaPipe models takes a moment; keep it off the main thread.
            detectorJob = viewModelScope.launch {
                var unused: LiveLandmarkDetector? = null
                try {
                    val loaded = withContext(defaultDispatcher) {
                        LiveLandmarkDetector(appContext).also { unused = it }
                    }
                    if (boundDanceId != null && detector == null) {
                        detector = loaded
                        unused = null
                    }
                } finally {
                    // Also when the screen closed while the models were loading.
                    unused?.close()
                }
            }
        }
    }

    // ── Camera frames (camera analysis thread) ───────────────────────────────

    /** Detects and scores one front-camera frame. Call from the analysis thread. */
    fun onCameraFrame(bitmap: Bitmap) {
        if (testMode) return
        val currentDetector = detector ?: return
        val primaryFrame = HolisticFrameSelector.primaryPose(currentDetector.detect(bitmap)) ?: return
        val primaryPose = primaryFrame.pose
        if (primaryPose.isEmpty()) return
        val leftHand = primaryFrame.leftHand
        val rightHand = primaryFrame.rightHand

        val current = _uiState.value
        if (!current.isPracticeActive) {
            _uiState.update {
                it.copy(
                    learnerLandmarks = primaryPose,
                    learnerLeftHandLandmarks = leftHand,
                    learnerRightHandLandmarks = rightHand
                )
            }
            return
        }

        val timeMs = current.practiceTimeMs
        // Detection takes a while; if the attempt ends or restarts meanwhile
        // (reset, submit, step done), this frame must not score.
        val attemptEval = similarityEval
        val attemptHandEval = handSimilarityEval
        // Body scoring remains pose-only. Hand landmarks are retained separately.
        val snap = attemptEval?.let { ev ->
            ev.onFrame(primaryPose, timeMs)
            ev.snapshot(includeSeries = false)
        }
        val handSnap = attemptHandEval?.let { handEv ->
            handEv.onFrame(leftHand, rightHand, timeMs)
            handEv.snapshot()
        }

        _uiState.update { state ->
            if (!state.isPracticeActive || attemptEval !== similarityEval || attemptHandEval !== handSimilarityEval) {
                return@update state.copy(
                    learnerLandmarks = primaryPose,
                    learnerLeftHandLandmarks = leftHand,
                    learnerRightHandLandmarks = rightHand
                )
            }
            val bodyScoreForCombined = snap?.alignmentScore ?: state.alignmentScore
            val live = snap?.liveScore
            val comboStart = if (live != null && live >= LIVE_GOOD_SCORE) state.comboStartMs ?: timeMs else null
            var next = state.copy(
                learnerLandmarks = primaryPose,
                learnerLeftHandLandmarks = leftHand,
                learnerRightHandLandmarks = rightHand,
                liveScore = live,
                comboStartMs = comboStart,
                comboSeconds = comboStart?.let { ((timeMs - it) / 1000L).toInt() } ?: 0,
                handScore = handSnap?.handScore,
                combinedPracticeScore = HandSimilarityEvaluator.combineWithBodyScore(
                    bodyScore = bodyScoreForCombined,
                    handScore = handSnap?.handScore,
                    hasReferenceHands = handSnap?.hasReferenceHands == true
                ),
                handFeedback = handSnap?.latestFeedback
            )
            next = if (snap != null) {
                next.copy(
                    alignmentScore = snap.alignmentScore,
                    timingConsistencyScore = snap.timingConsistencyScore,
                    smoothnessScore = snap.smoothnessScore,
                    avgPoseError = snap.avgPoseError,
                    frameCount = snap.frameCount,
                    wordFeedback = snap.latestFeedback?.message,
                    latestFeedbackMessages = snap.feedbackMessages + handSnap?.feedbackMessages.orEmpty()
                )
            } else {
                next.copy(frameCount = state.frameCount + 1)
            }
            next
        }
    }

    // ── Reference dancer and step-by-step mode ───────────────────────────────

    fun selectReferenceTrack(trackId: String) {
        val state = _uiState.value
        if (trackId == state.selectedReferenceTrack?.trackId) return
        val track = state.availableReferenceTracks.firstOrNull { it.trackId == trackId } ?: return
        selectTrackInternal(track.trackId)
        reset(message = "Using ${track.label}. Tap Start Practice when ready.")
    }

    fun selectFullRoutine() {
        _uiState.update { it.copy(practiceMode = PracticeMode.FULL_ROUTINE) }
        updateActiveTemplate()
        reset(message = "Full routine ready. Tap Start Practice when ready.")
    }

    fun selectStepByStep() {
        val segments = _uiState.value.practiceSegments
        if (segments.isEmpty()) return
        _uiState.update {
            it.copy(
                practiceMode = PracticeMode.STEP_BY_STEP,
                currentStepIndex = it.currentStepIndex.coerceIn(0, segments.lastIndex)
            )
        }
        updateActiveTemplate()
        reset(message = "${segments[_uiState.value.currentStepIndex].title} ready. Tap Start Practice when ready.")
    }

    fun previousStep() {
        val state = _uiState.value
        if (state.currentStepIndex == 0) return
        goToStep(state.currentStepIndex - 1)
    }

    fun nextStep() {
        val state = _uiState.value
        if (state.currentStepIndex >= state.practiceSegments.lastIndex) return
        goToStep(state.currentStepIndex + 1)
    }

    /** The secondary button while idle: Submit, Next Step or Restart Step. */
    fun onSecondaryAction(uid: String): PracticeSubmission? {
        val state = _uiState.value
        if (state.practiceMode != PracticeMode.STEP_BY_STEP) return submit(uid)
        if (state.currentStepIndex < state.practiceSegments.lastIndex) {
            goToStep(state.currentStepIndex + 1)
        } else {
            reset(message = "Final step ready. Tap Start Practice to repeat it.")
        }
        return null
    }

    /** The primary button while practising: Finish Step or Submit. */
    fun onFinishAction(uid: String): PracticeSubmission? {
        return if (_uiState.value.practiceMode == PracticeMode.STEP_BY_STEP) {
            completeCurrentStep()
            null
        } else {
            submit(uid)
        }
    }

    private fun goToStep(index: Int) {
        _uiState.update { it.copy(currentStepIndex = index) }
        updateActiveTemplate()
        reset(message = "${_uiState.value.practiceSegments[index].title} ready. Tap Start Practice when ready.")
    }

    private fun selectTrackInternal(trackId: String?) {
        val source = template
        val track = source?.resolveTrack(trackId)
        val frames = track?.frames ?: source?.frames.orEmpty()
        selectedTrackTemplate = if (source == null || frames.isEmpty()) {
            null
        } else {
            source.copy(
                frames = frames,
                tracks = track?.let(::listOf).orEmpty(),
                defaultTrackId = track?.trackId ?: source.defaultTrackId,
                handTracks = source.handTracks.filter { handTrack ->
                    handTrack.trackId == track?.trackId ||
                        (track == null && handTrack.trackId == source.defaultTrackId)
                }
            )
        }
        val segments = derivePracticeStepSegments(selectedTrackTemplate)
        _uiState.update {
            if (segments.size <= 1) {
                it.copy(
                    selectedReferenceTrack = track,
                    practiceSegments = segments,
                    practiceMode = PracticeMode.FULL_ROUTINE,
                    currentStepIndex = 0
                )
            } else {
                it.copy(
                    selectedReferenceTrack = track,
                    practiceSegments = segments,
                    currentStepIndex = it.currentStepIndex.coerceIn(0, segments.lastIndex)
                )
            }
        }
        updateActiveTemplate()
    }

    private fun updateActiveTemplate() {
        saveSelection()
        val segment = _uiState.value.currentPracticeSegment
        activeTemplate = if (segment != null) {
            sliceTemplateForStep(selectedTrackTemplate, segment) ?: selectedTrackTemplate
        } else {
            selectedTrackTemplate
        }
        newEvaluators()
    }

    private fun saveSelection() {
        val danceId = boundDanceId ?: return
        val state = _uiState.value
        savedState[KEY_DANCE_ID] = danceId
        savedState[KEY_TRACK_ID] = state.selectedReferenceTrack?.trackId
        savedState[KEY_MODE] = state.practiceMode.name
        savedState[KEY_STEP] = state.currentStepIndex
    }

    /** Fresh scoring for a new attempt, so earlier attempts don't count. */
    private fun newEvaluators() {
        val active = activeTemplate
        if (testMode || active == null) {
            similarityEval = null
            handSimilarityEval = null
            return
        }
        similarityEval = active.frames.takeIf { it.isNotEmpty() }?.let {
            PoseSimilarityEvaluator(template = active, mirrorReferenceX = true)
        }
        handSimilarityEval = active
            .takeIf { it.handTracks.any { handTrack -> handTrack.frames.isNotEmpty() } }
            ?.let {
                HandSimilarityEvaluator(
                    template = it,
                    referenceTrackId = _uiState.value.selectedReferenceTrack?.trackId
                )
            }
    }

    // ── Countdown, timer, reset ──────────────────────────────────────────────

    fun reset(message: String = "Reset") {
        countdownJob?.cancel()
        timerJob?.cancel()
        tsStart = System.currentTimeMillis()
        newEvaluators()
        _uiState.update {
            resetScores(it).copy(
                countdownSecondsRemaining = null,
                isPracticeActive = false,
                showReferenceSelector = true,
                practiceTimeMs = 0L,
                learnerLandmarks = null,
                learnerLeftHandLandmarks = emptyList(),
                learnerRightHandLandmarks = emptyList(),
                refLandmarksForCamera = null,
                latestTimelinePoints = emptyList(),
                latestSegments = emptyList(),
                status = message
            )
        }
    }

    fun startPracticeCountdown() {
        countdownJob?.cancel()
        timerJob?.cancel()
        newEvaluators()
        val segment = _uiState.value.currentPracticeSegment
        _uiState.update {
            resetScores(it).copy(
                countdownSecondsRemaining = 3,
                isPracticeActive = false,
                showReferenceSelector = false,
                latestTimelinePoints = emptyList(),
                latestSegments = emptyList(),
                learnerLandmarks = null,
                learnerLeftHandLandmarks = emptyList(),
                learnerRightHandLandmarks = emptyList(),
                refLandmarksForCamera = null,
                practiceTimeMs = 0L,
                status = if (segment != null) "Starting ${segment.title.lowercase()} in 3..." else "Starting in 3..."
            )
        }
        countdownJob = viewModelScope.launch(timerDispatcher) {
            for (next in 2 downTo 1) {
                delay(1_000)
                _uiState.update { it.copy(countdownSecondsRemaining = next, status = "Starting in $next...") }
            }
            delay(1_000)
            startPractice()
        }
    }

    private fun startPractice() {
        tsStart = System.currentTimeMillis()
        val segment = _uiState.value.currentPracticeSegment
        _uiState.update {
            it.copy(
                countdownSecondsRemaining = null,
                practiceTimeMs = 0L,
                isPracticeActive = true,
                status = if (segment != null) "${segment.title} in progress" else "Practice in progress"
            )
        }
        startTimer()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch(timerDispatcher) {
            while (isActive && _uiState.value.isPracticeActive) {
                onTimerTick(System.currentTimeMillis() - tsStart)
                delay(33)
            }
        }
    }

    private fun onTimerTick(practiceTimeMs: Long) {
        val frames = if (testMode) emptyList() else activeTemplate?.frames.orEmpty()
        val ref = frames.minByOrNull { abs(it.tMs - practiceTimeMs) }?.landmarks
        _uiState.update {
            it.copy(
                practiceTimeMs = practiceTimeMs,
                refLandmarksForCamera = ref?.let(::mirrorX)?.let(::centerPoseOnScreen)
            )
        }
        val segment = _uiState.value.currentPracticeSegment
        if (!testMode && segment != null && practiceTimeMs >= segment.durationMs) {
            completeCurrentStep()
        }
    }

    private fun completeCurrentStep() {
        countdownJob?.cancel()
        timerJob?.cancel()
        val snap = similarityEval?.snapshot(includeSeries = true)
        val handSnap = if (snap != null) handSimilarityEval?.snapshot() else null
        _uiState.update { state ->
            val scored = if (snap != null) {
                state.copy(
                    alignmentScore = snap.alignmentScore,
                    timingConsistencyScore = snap.timingConsistencyScore,
                    smoothnessScore = snap.smoothnessScore,
                    avgPoseError = snap.avgPoseError,
                    frameCount = snap.frameCount,
                    wordFeedback = snap.latestFeedback?.message,
                    latestTimelinePoints = snap.timelinePoints,
                    latestSegments = snap.performanceSegments,
                    latestFeedbackMessages = snap.feedbackMessages + handSnap?.feedbackMessages.orEmpty(),
                    handScore = handSnap?.handScore,
                    combinedPracticeScore = HandSimilarityEvaluator.combineWithBodyScore(
                        bodyScore = snap.alignmentScore,
                        handScore = handSnap?.handScore,
                        hasReferenceHands = handSnap?.hasReferenceHands == true
                    ),
                    handFeedback = handSnap?.latestFeedback
                )
            } else {
                state
            }
            val segment = scored.currentPracticeSegment
            scored.copy(
                countdownSecondsRemaining = null,
                isPracticeActive = false,
                practiceTimeMs = 0L,
                refLandmarksForCamera = null,
                status = when {
                    segment == null -> "Practice paused."
                    scored.currentStepIndex < scored.practiceSegments.lastIndex ->
                        "${segment.title} complete. Tap Next Step to continue."
                    else -> "${segment.title} complete. Repeat it or switch to Full Routine to submit a session."
                }
            )
        }
    }

    fun setStatus(message: String?) {
        _uiState.update { it.copy(status = message) }
    }

    // ── Submit ───────────────────────────────────────────────────────────────

    /**
     * Ends a full-routine attempt and builds the session to save, or returns
     * null (with a status message) when there is nothing to submit.
     */
    private fun submit(uid: String): PracticeSubmission? {
        val dance = item ?: return null
        countdownJob?.cancel()
        _uiState.update { it.copy(countdownSecondsRemaining = null) }
        val state = _uiState.value
        if (state.practiceMode == PracticeMode.STEP_BY_STEP) {
            setStatus("Step-by-step mode is for rehearsal only. Switch to Full Routine to submit a session.")
            return null
        }
        if (!state.isPracticeActive && state.frameCount == 0) {
            setStatus("Tap Start Practice before submitting a dance session.")
            return null
        }
        val educatorUid = dance.educatorUid?.trim().takeUnless { it.isNullOrBlank() }
        if (educatorUid == null) {
            setStatus("This dance is missing educator ownership data, so analytics cannot be submitted.")
            return null
        }

        timerJob?.cancel()
        val tsEnd = System.currentTimeMillis()
        val snapshot = similarityEval?.snapshot(includeSeries = true)
        val handSnapshot = handSimilarityEval?.snapshot()
        val payloadTimeline = snapshot?.timelinePoints ?: state.latestTimelinePoints
        val payloadSegments = snapshot?.performanceSegments ?: state.latestSegments
        val payloadFeedbackMessages =
            (snapshot?.feedbackMessages ?: state.latestFeedbackMessages) + handSnapshot?.feedbackMessages.orEmpty()
        val track = state.selectedReferenceTrack

        val analysisPayload = SessionAnalysisPayload(
            danceId = dance.danceId,
            alignmentScore = state.alignmentScore,
            timingConsistencyScore = state.timingConsistencyScore,
            smoothnessScore = state.smoothnessScore,
            avgPoseError = state.avgPoseError,
            frames = state.frameCount,
            educatorUid = educatorUid,
            videoUrl = dance.videoUrl,
            videoPositionMs = state.practiceTimeMs,
            referenceTrackId = track?.trackId,
            referenceTrackLabel = track?.label,
            feedbackMessages = payloadFeedbackMessages,
            timelinePoints = payloadTimeline,
            performanceSegments = payloadSegments
        )

        val session = DanceSessionEntity(
            sessionId = Ids.newSessionId(),
            tsStart = tsStart,
            tsEnd = tsEnd,
            activityName = dance.title,
            activityType = "LIVE_CAMERA_TEMPLATE",
            durationMs = tsEnd - tsStart,
            smoothnessScore = state.smoothnessScore,
            balanceScore = 1.0,
            timingConsistencyScore = state.timingConsistencyScore,
            alignmentScore = state.alignmentScore,
            qualityFlag = if (state.alignmentScore >= 0.75) "GOOD" else "DONE",
            analysisJson = analysisPayload.toJson(),
            ownerUid = uid
        )

        val result = PracticeResult(
            danceId = dance.danceId,
            danceTitle = dance.title,
            alignment = state.alignmentScore,
            timing = state.timingConsistencyScore,
            smoothness = state.smoothnessScore,
            isPersonalBest = personalBests.recordIfBest(uid, dance.danceId, state.alignmentScore)
        )

        _uiState.update {
            it.copy(
                isPracticeActive = false,
                practiceTimeMs = 0L,
                refLandmarksForCamera = null,
                latestTimelinePoints = payloadTimeline,
                latestSegments = payloadSegments,
                latestFeedbackMessages = payloadFeedbackMessages,
                handScore = handSnapshot?.handScore,
                combinedPracticeScore = HandSimilarityEvaluator.combineWithBodyScore(
                    bodyScore = it.alignmentScore,
                    handScore = handSnapshot?.handScore,
                    hasReferenceHands = handSnapshot?.hasReferenceHands == true
                ),
                handFeedback = handSnapshot?.latestFeedback,
                lastResult = result,
                status = "Submitting practice result..."
            )
        }
        return PracticeSubmission(session = session)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun resetScores(state: PracticeUiState): PracticeUiState = state.copy(
        alignmentScore = if (testMode) 0.82 else 0.0,
        timingConsistencyScore = if (testMode) 0.91 else 1.0,
        smoothnessScore = if (testMode) 0.88 else 1.0,
        handScore = null,
        liveScore = if (testMode) 0.82 else null,
        comboStartMs = null,
        comboSeconds = 0,
        combinedPracticeScore = if (testMode) 0.82 else 0.0,
        avgPoseError = if (testMode) 0.1234 else 0.0,
        frameCount = if (testMode) 42 else 0,
        latestFeedbackMessages = emptyList(),
        wordFeedback = if (testMode) "Nice match. Keep the timing steady." else null,
        handFeedback = null
    )

    private fun mirrorX(flat: List<Float>): List<Float> {
        if (flat.isEmpty()) return flat
        val out = flat.toMutableList()
        for (i in 0 until out.size / 4) {
            val base = i * 4
            out[base] = 1f - out[base]
        }
        return out
    }

    private fun centerPoseOnScreen(flat: List<Float>): List<Float> {
        val bounds = flat.poseBounds(padding = 0f) ?: return flat
        val out = flat.toMutableList()
        val dx = 0.5f - bounds.centerX
        val dy = 0.5f - bounds.centerY
        for (i in 0 until out.size / 4) {
            val base = i * 4
            out[base] = (out[base] + dx).coerceIn(0f, 1f)
            out[base + 1] = (out[base + 1] + dy).coerceIn(0f, 1f)
        }
        return out
    }
}

/** MediaPipe Holistic (body + hands), falling back to body-only pose. */
private class LiveLandmarkDetector(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val holisticHelper = if (appContext.assets.assetExists(HolisticLandmarkerHelper.MODEL_ASSET)) {
        runCatching { HolisticLandmarkerHelper(appContext) }.getOrNull()
    } else {
        null
    }
    private var fallbackPoseHelper: PoseLandmarkerHelper? = null
    private var closed = false

    // detect() runs on the camera analysis thread and close() on the main thread;
    // the lock stops the MediaPipe models being closed mid-frame.
    @Synchronized
    fun detect(bitmap: Bitmap): List<HolisticFrameLandmarks> {
        if (closed) return emptyList()
        val holistic = holisticHelper
        if (holistic != null) {
            val result = runCatching { holistic.detect(bitmap) }.getOrNull()
            if (result != null) {
                return listOf(result)
            }
        }

        val poseHelper = fallbackPoseHelper ?: runCatching { PoseLandmarkerHelper(appContext) }
            .getOrNull()
            ?.also { fallbackPoseHelper = it }
            ?: return emptyList()

        return runCatching { poseHelper.detect(bitmap) }.getOrNull()
            ?.landmarks()
            .orEmpty()
            .mapNotNull { pose ->
                if (pose.isEmpty()) return@mapNotNull null
                val flat = ArrayList<Float>(pose.size * 4)
                for (landmark in pose) {
                    flat.add(landmark.x())
                    flat.add(landmark.y())
                    flat.add(landmark.z())
                    flat.add(landmark.visibility().orElse(1.0f))
                }
                HolisticFrameLandmarks(pose = flat)
            }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { holisticHelper?.close() }
        runCatching { fallbackPoseHelper?.close() }
    }
}

private fun AssetManager.assetExists(assetPath: String): Boolean {
    return runCatching {
        open(assetPath).use { true }
    }.getOrDefault(false)
}
