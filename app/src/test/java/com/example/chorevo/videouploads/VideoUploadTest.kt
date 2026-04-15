package com.example.chorevo.videouploads

import android.content.Context
import android.net.Uri
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.testing.MainDispatcherRule
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel
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
            videoUploadRepository = uploadsRepo
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

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo)
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

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo)
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

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo)
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
                generateTemplate = true,
                fps = 15,
                onProgress = any(),
                onStageChanged = any(),
                onTemplateFailure = any(),
                continueWithoutTemplateOnFailure = true
            )
        } returns Result.success("dance-id")

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo)
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
                generateTemplate = true,
                fps = 15,
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
    fun saveEdit_guardClause_invalidDraft_doesNotCallRepository() = runTest {
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(emptyList())

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo)
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

        coVerify(exactly = 0) { uploadsRepo.updateDance(any(), any(), any(), any()) }
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
                description = any()
            )
        } returns Result.success(Unit)

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo)
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
                description = "NewDesc"
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

        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo)
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
}
