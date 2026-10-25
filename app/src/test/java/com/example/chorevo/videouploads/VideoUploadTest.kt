package com.example.chorevo.videouploads

import android.content.Context
import android.net.Uri
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.repository.FirebaseAuthRepository
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.posedetection.PoseAnalysisWarning
import com.example.chorevo.posedetection.PoseBounds
import com.example.chorevo.posedetection.PoseFrame
import com.example.chorevo.posedetection.HandLandmarkFrame
import com.example.chorevo.posedetection.HandTrack
import com.example.chorevo.posedetection.PoseSelectionCandidate
import com.example.chorevo.posedetection.PoseSelectionPreview
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTrack
import com.example.chorevo.testing.MainDispatcherRule
import com.example.chorevo.videouploads.viewmodel.DancerReviewUiState
import com.example.chorevo.videouploads.viewmodel.VideoUploadsViewModel
import com.example.chorevo.videouploads.viewmodel.confirmedTemplate
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoUploadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var authRepo: FirebaseAuthRepository
    private lateinit var uploadsRepo: VideoUploadsRepository

    @Before
    fun setUp() {
        authRepo = mockk()
        uploadsRepo = mockk()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun refreshMyUploads_notLoggedIn_setsEmptyListAndStatusMessage() = runTest {
        coEvery { authRepo.getCurrentUserProfile() } returns null

        val vm = VideoUploadsViewModel(
            authRepository = authRepo,
            videoUploadRepository = uploadsRepo,
            templateGenerator = mockk(relaxed = true)
        )
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.refreshing)
        assertTrue(s.myUploads.isEmpty())
        assertEquals("Not logged in.", s.statusMessage)

        coVerify(exactly = 0) { uploadsRepo.loadVideos(any()) }
    }

    @Test
    fun refreshMyUploads_success_updatesMyUploads() = runTest {
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user

        val item1 = mockk<DanceLibraryModel>(relaxed = true)
        val item2 = mockk<DanceLibraryModel>(relaxed = true)
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(listOf(item1, item2))

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.refreshing)
        assertEquals(2, s.myUploads.size)
        assertSame(item1, s.myUploads[0])
        assertSame(item2, s.myUploads[1])

        coVerify(exactly = 1) { uploadsRepo.loadVideos("uid123") }
    }

    @Test
    fun refreshMyUploads_failure_setsStatusMessage() = runTest {
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.failure(RuntimeException("boom"))

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.refreshing)
        assertNotNull(s.statusMessage)
        assertTrue(s.statusMessage!!.startsWith("Failed to load uploads:"))

        coVerify(exactly = 1) { uploadsRepo.loadVideos("uid123") }
    }

    @Test
    fun uploadDraft_actions_updateFormAndDialogState() = runTest {
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(emptyList())

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        val galleryUri = mockk<Uri> {
            every { lastPathSegment } returns "10"
        }

        vm.uploadTitleChanged("Routine")
        vm.uploadDanceTypeChanged("Hip Hop")
        vm.uploadDescriptionChanged("Fast footwork")
        vm.openVideoSourceDialog()
        vm.selectGalleryVideo(galleryUri)
        vm.dismissVideoSourceDialog()

        val s = vm.uiState.value
        assertEquals("Routine", s.uploadDraft.title)
        assertEquals("Hip Hop", s.uploadDraft.danceType)
        assertEquals("Fast footwork", s.uploadDraft.description)
        assertEquals(galleryUri, s.uploadDraft.selectedVideoUri)
        assertEquals("10", s.uploadDraft.selectedVideoLabel)
        assertFalse(s.showVideoSourceDialog)
        assertTrue(s.uploadDraft.canUpload)
    }

    @Test
    fun uploadDance_success_usesUploadDraftAndClearsForm() = runTest {
        val user = mockk<UserProfile> {
            every { uid } returns "uid123"
            every { displayName } returns "Coach A"
            every { email } returns "coach@example.com"
        }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val recordedUri = mockk<Uri>(relaxed = true)

        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(emptyList())
        coEvery {
            uploadsRepo.uploadDance(
                context = context,
                educatorUid = "uid123",
                educatorName = "Coach A",
                title = "Routine",
                danceType = "Hip Hop",
                description = "Fast footwork",
                videoUri = recordedUri,
                visibility = DanceLibraryModel.VISIBILITY_PUBLIC,
                generateTemplate = true,
                confirmedTemplate = null,
                fps = 15,
                collectHandLandmarks = true,
                onProgress = any(),
                onStageChanged = any(),
                onTemplateFailure = any(),
                continueWithoutTemplateOnFailure = true
            )
        } returns Result.success("dance-id")

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        vm.uploadTitleChanged("Routine")
        vm.uploadDanceTypeChanged("Hip Hop")
        vm.uploadDescriptionChanged("Fast footwork")
        vm.selectRecordedVideo(recordedUri)

        vm.uploadDance(context = context, generateTemplate = true, fps = 15)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            uploadsRepo.uploadDance(
                context = context,
                educatorUid = "uid123",
                educatorName = "Coach A",
                title = "Routine",
                danceType = "Hip Hop",
                description = "Fast footwork",
                videoUri = recordedUri,
                visibility = DanceLibraryModel.VISIBILITY_PUBLIC,
                generateTemplate = true,
                confirmedTemplate = null,
                fps = 15,
                collectHandLandmarks = true,
                onProgress = any(),
                onStageChanged = any(),
                onTemplateFailure = any(),
                continueWithoutTemplateOnFailure = true
            )
        }

        val s = vm.uiState.value
        assertEquals(
            "Uploaded. Template generation continues in the background.",
            s.statusMessage
        )
        assertEquals("", s.uploadDraft.title)
        assertEquals("", s.uploadDraft.danceType)
        assertEquals("", s.uploadDraft.description)
        assertNull(s.uploadDraft.selectedVideoUri)
        assertNull(s.uploadDraft.selectedVideoLabel)
    }

    @Test
    fun dancerReviewState_exposesDetectedDancerCountAndSafeWarningsWhenAbsent() {
        val state = DancerReviewUiState()

        assertEquals(0, state.detectedCount)
        assertTrue(state.warningMessages.isEmpty())
        assertFalse(state.canConfirm)
    }

    @Test
    fun dancerReviewState_mapsQualityWarningsToCreatorFriendlyMessages() {
        val template = PoseTemplate(
            tracks = listOf(sampleTrack("track_1")),
            qualityWarnings = listOf(
                PoseAnalysisWarning("possibleDuplicateTracks", "Internal duplicate detail"),
                PoseAnalysisWarning("lowLightQuality", "Internal low light detail"),
                PoseAnalysisWarning("missingFrames", "Internal missing detail"),
                PoseAnalysisWarning("lowLandmarkConfidence", "Internal confidence detail")
            )
        )
        val state = DancerReviewUiState(
            template = template,
            detectedDancerCount = 1,
            selectedTrackIds = setOf("track_1"),
            defaultTrackId = "track_1"
        )

        assertEquals(1, state.detectedCount)
        assertEquals(4, state.warningMessages.size)
        assertTrue(state.warningMessages.any { it.contains("duplicate dancer tracks") })
        assertTrue(state.warningMessages.any { it.contains("dim") })
        assertTrue(state.warningMessages.any { it.contains("Some frames") })
        assertTrue(state.warningMessages.any { it.contains("low-confidence") })
    }

    @Test
    fun confirmedTemplate_preservesSelectedDefaultTrackForMultipleDancers() {
        val template = PoseTemplate(
            fps = 8,
            frames = sampleFrames("track_1"),
            tracks = listOf(sampleTrack("track_1"), sampleTrack("track_2")),
            defaultTrackId = "track_1",
            selectionPreview = PoseSelectionPreview(
                timeMs = 100,
                frameWidth = 100,
                frameHeight = 100,
                candidates = listOf(
                    sampleCandidate("track_1"),
                    sampleCandidate("track_2")
                )
            ),
            handTracks = listOf(
                HandTrack(
                    trackId = "track_1",
                    frames = listOf(HandLandmarkFrame(tMs = 0, leftHand = listOf(0.1f, 0.2f, 0f, 1f)))
                ),
                HandTrack(
                    trackId = "track_2",
                    frames = listOf(HandLandmarkFrame(tMs = 0, rightHand = listOf(0.3f, 0.4f, 0f, 1f)))
                )
            )
        )

        val confirmed = template.confirmedTemplate(
            selectedTrackIds = setOf("track_1", "track_2"),
            defaultTrackId = "track_2"
        )

        assertEquals("track_2", confirmed.defaultTrackId)
        assertEquals(sampleFrames("track_2"), confirmed.frames)
        assertEquals(2, confirmed.availableTracks().size)
        assertEquals(2, confirmed.selectionPreview?.candidates?.size)
        assertEquals(listOf("track_1", "track_2"), confirmed.handTracks.map { it.trackId })
    }

    @Test
    fun confirmedTemplate_filtersHandTracksToSelectedDancers() {
        val template = PoseTemplate(
            fps = 8,
            tracks = listOf(sampleTrack("track_1"), sampleTrack("track_2")),
            defaultTrackId = "track_1",
            handTracks = listOf(
                HandTrack(
                    trackId = "track_1",
                    frames = listOf(HandLandmarkFrame(tMs = 0, leftHand = listOf(0.1f, 0.2f, 0f, 1f)))
                ),
                HandTrack(
                    trackId = "track_2",
                    frames = listOf(HandLandmarkFrame(tMs = 0, rightHand = listOf(0.3f, 0.4f, 0f, 1f)))
                )
            )
        )

        val confirmed = template.confirmedTemplate(
            selectedTrackIds = setOf("track_2"),
            defaultTrackId = "track_2"
        )

        assertEquals(listOf("track_2"), confirmed.handTracks.map { it.trackId })
        assertEquals("track_2", confirmed.defaultTrackId)
        assertEquals(sampleFrames("track_2"), confirmed.frames)
    }

    @Test
    fun confirmedTemplate_stillWorksWithOneDancer() {
        val template = PoseTemplate(
            fps = 8,
            frames = sampleFrames("track_1"),
            tracks = listOf(sampleTrack("track_1")),
            defaultTrackId = "track_1",
            selectionPreview = PoseSelectionPreview(
                candidates = listOf(sampleCandidate("track_1"))
            )
        )

        val confirmed = template.confirmedTemplate(
            selectedTrackIds = setOf("track_1"),
            defaultTrackId = "track_1"
        )

        assertEquals("track_1", confirmed.defaultTrackId)
        assertEquals(1, confirmed.availableTracks().size)
        assertEquals(sampleFrames("track_1"), confirmed.frames)
    }

    @Test
    fun saveEdit_guardClause_invalidDraft_doesNotCallRepository() = runTest {
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(emptyList())

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        val item = mockk<DanceLibraryModel>(relaxed = true) {
            every { danceId } returns "d1"
            every { title } returns "Old"
            every { danceType } returns "Type"
            every { description } returns "Desc"
        }
        vm.openEdit(item)
        vm.editTitleChanged("")

        vm.saveEdit()
        advanceUntilIdle()

        coVerify(exactly = 0) { uploadsRepo.updateDance(any(), any(), any(), any(), any()) }
        assertFalse(vm.uiState.value.saving)
        assertNotNull(vm.uiState.value.editDraft)
    }

    @Test
    fun saveEdit_success_callsUpdate_thenClosesDraft_andRefreshes() = runTest {
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(emptyList())
        coEvery {
            uploadsRepo.updateDance(
                danceId = "d1",
                title = any(),
                danceType = any(),
                description = any(),
                visibility = any()
            )
        } returns Result.success(Unit)

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        val item = mockk<DanceLibraryModel>(relaxed = true) {
            every { danceId } returns "d1"
            every { title } returns "Old"
            every { danceType } returns "OldType"
            every { description } returns "OldDesc"
        }

        vm.openEdit(item)
        vm.editTitleChanged("NewTitle")
        vm.editDanceTypeChanged("NewType")
        vm.editDescriptionChanged("NewDesc")

        vm.saveEdit()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            uploadsRepo.updateDance(
                danceId = "d1",
                title = "NewTitle",
                danceType = "NewType",
                description = "NewDesc",
                visibility = any()
            )
        }

        val s = vm.uiState.value
        assertFalse(s.saving)
        assertNull(s.editDraft)
        assertEquals("Updated", s.statusMessage)
        coVerify(atLeast = 2) { uploadsRepo.loadVideos("uid123") }
    }

    @Test
    fun confirmDelete_success_callsDelete_thenClearsDialog_andRefreshes() = runTest {
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(emptyList())
        coEvery { uploadsRepo.deleteDance("d1", "uid123") } returns Result.success(Unit)

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        val item = mockk<DanceLibraryModel>(relaxed = true) {
            every { danceId } returns "d1"
            every { educatorUid } returns "uid123"
        }

        vm.openDelete(item)
        vm.confirmDelete()
        advanceUntilIdle()

        coVerify(exactly = 1) { uploadsRepo.deleteDance("d1", "uid123") }

        val s = vm.uiState.value
        assertFalse(s.deleting)
        assertNull(s.deleteItem)
        assertEquals("Deleted", s.statusMessage)
        coVerify(atLeast = 2) { uploadsRepo.loadVideos("uid123") }
    }

    @Test
    fun setVisibility_success_savesAndUpdatesListedUpload() = runTest {
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        val upload = DanceLibraryModel(danceId = "d1", title = "Routine", educatorUid = "uid123")
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(listOf(upload))
        coEvery {
            uploadsRepo.setVisibility("d1", DanceLibraryModel.VISIBILITY_PRIVATE)
        } returns Result.success(Unit)

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        vm.setVisibility(upload, DanceLibraryModel.VISIBILITY_PRIVATE)
        advanceUntilIdle()

        coVerify(exactly = 1) { uploadsRepo.setVisibility("d1", DanceLibraryModel.VISIBILITY_PRIVATE) }
        val s = vm.uiState.value
        assertTrue(s.myUploads.single().isPrivate)
        assertTrue(s.updatingVisibilityIds.isEmpty())
        assertEquals("Video is now private", s.statusMessage)
    }

    @Test
    fun setVisibility_failure_keepsOriginalVisibility() = runTest {
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        val upload = DanceLibraryModel(danceId = "d1", title = "Routine", educatorUid = "uid123")
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(listOf(upload))
        coEvery { uploadsRepo.setVisibility(any(), any()) } returns Result.failure(RuntimeException("offline"))

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        vm.setVisibility(upload, DanceLibraryModel.VISIBILITY_PRIVATE)
        advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.myUploads.single().isPrivate)
        assertEquals("Could not change visibility: offline", s.statusMessage)
    }

    @Test
    fun uploadDance_withPrivateChoice_uploadsAsPrivate() = runTest {
        val user = mockk<UserProfile> {
            every { uid } returns "uid123"
            every { displayName } returns "Coach A"
            every { email } returns "coach@example.com"
        }
        val context = mockk<Context>(relaxed = true)
        val videoUri = mockk<Uri>(relaxed = true)
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(emptyList())
        coEvery {
            uploadsRepo.uploadDance(
                context = any(), educatorUid = any(), educatorName = any(), title = any(),
                danceType = any(), description = any(), videoUri = any(), visibility = any(),
                generateTemplate = any(), confirmedTemplate = any(), fps = any(),
                collectHandLandmarks = any(), onProgress = any(), onStageChanged = any(),
                onTemplateFailure = any(), continueWithoutTemplateOnFailure = any()
            )
        } returns Result.success("dance-id")

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        vm.uploadTitleChanged("Routine")
        vm.uploadDanceTypeChanged("Hip Hop")
        vm.uploadDescriptionChanged("Fast footwork")
        vm.selectRecordedVideo(videoUri)
        vm.uploadVisibilityChanged(DanceLibraryModel.VISIBILITY_PRIVATE)

        vm.uploadDance(context = context, generateTemplate = true, fps = 15)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            uploadsRepo.uploadDance(
                context = any(), educatorUid = any(), educatorName = any(), title = any(),
                danceType = any(), description = any(), videoUri = any(),
                visibility = DanceLibraryModel.VISIBILITY_PRIVATE,
                generateTemplate = any(), confirmedTemplate = any(), fps = any(),
                collectHandLandmarks = any(), onProgress = any(), onStageChanged = any(),
                onTemplateFailure = any(), continueWithoutTemplateOnFailure = any()
            )
        }
    }

    @Test
    fun uploadDance_withChosenCoverFrame_passesCoverFrame() = runTest {
        val user = mockk<UserProfile> {
            every { uid } returns "uid123"
            every { displayName } returns "Coach A"
            every { email } returns "coach@example.com"
        }
        val context = mockk<Context>(relaxed = true)
        val videoUri = mockk<Uri>(relaxed = true)
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(emptyList())
        coEvery {
            uploadsRepo.uploadDance(
                context = any(), educatorUid = any(), educatorName = any(), title = any(),
                danceType = any(), description = any(), videoUri = any(), visibility = any(), coverFrameMs = any(),
                generateTemplate = any(), confirmedTemplate = any(), fps = any(),
                collectHandLandmarks = any(), onProgress = any(), onStageChanged = any(),
                onTemplateFailure = any(), continueWithoutTemplateOnFailure = any()
            )
        } returns Result.success("dance-id")

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo, templateGenerator = mockk(relaxed = true))
        advanceUntilIdle()

        vm.uploadTitleChanged("Routine")
        vm.uploadDanceTypeChanged("Hip Hop")
        vm.uploadDescriptionChanged("Fast footwork")
        vm.selectRecordedVideo(videoUri)
        vm.uploadCoverFrameChanged(4_500L)

        vm.uploadDance(context = context, generateTemplate = true, fps = 15)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            uploadsRepo.uploadDance(
                context = any(), educatorUid = any(), educatorName = any(), title = any(),
                danceType = any(), description = any(), videoUri = any(),
                visibility = any(), coverFrameMs = 4_500L,
                generateTemplate = any(), confirmedTemplate = any(), fps = any(),
                collectHandLandmarks = any(), onProgress = any(), onStageChanged = any(),
                onTemplateFailure = any(), continueWithoutTemplateOnFailure = any()
            )
        }
    }

    private fun sampleTrack(trackId: String): PoseTrack {
        return PoseTrack(
            trackId = trackId,
            label = trackId,
            frames = sampleFrames(trackId),
            avgVisibility = 0.8f
        )
    }

    private fun sampleFrames(seed: String): List<PoseFrame> {
        val offset = if (seed == "track_1") 0f else 0.1f
        return listOf(
            PoseFrame(
                tMs = 0,
                landmarks = listOf(
                    0.1f + offset,
                    0.2f,
                    0f,
                    0.9f
                )
            )
        )
    }

    private fun sampleCandidate(trackId: String): PoseSelectionCandidate {
        return PoseSelectionCandidate(
            trackId = trackId,
            landmarks = sampleFrames(trackId).first().landmarks,
            bounds = PoseBounds(left = 0.1f, top = 0.1f, right = 0.5f, bottom = 0.9f)
        )
    }
}
