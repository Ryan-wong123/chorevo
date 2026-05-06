package com.example.chorevo.videouploads

import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.testing.MainDispatcherRule
import com.example.chorevo.videouploads.ViewModel.VideoUploadsViewModel
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoUploadsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var authRepo: FirebaseAuthRepository
    private lateinit var uploadsRepo: VideoUploadsRepository // <- make this your injectedvideoUploadRepo type

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
        // Arrange
        coEvery { authRepo.getCurrentUserProfile() } returns null

        // Act: init triggers refreshMyUploads()
        val vm = VideoUploadsViewModel(
            authRepository = authRepo,
            videoUploadRepository = uploadsRepo
        )
        advanceUntilIdle()

        // Assert
        val s = vm.uiState.value
        assertFalse(s.refreshing)
        assertTrue(s.myUploads.isEmpty())
        assertEquals("Not logged in.", s.statusMessage)

        coVerify(exactly = 0) { uploadsRepo.loadVideos(any()) }
    }

    @Test
    fun refreshMyUploads_success_updatesMyUploads() = runTest {
        // Arrange
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user

        val item1 = mockk<DanceLibraryModel>(relaxed = true)
        val item2 = mockk<DanceLibraryModel>(relaxed = true)

        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(listOf(item1, item2))

        // Act
        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo)
        advanceUntilIdle()

        // Assert
        val s = vm.uiState.value
        assertFalse(s.refreshing)
        assertEquals(2, s.myUploads.size)
        assertSame(item1, s.myUploads[0])
        assertSame(item2, s.myUploads[1])

        coVerify(exactly = 1) { uploadsRepo.loadVideos("uid123") }
    }

    @Test
    fun refreshMyUploads_failure_setsStatusMessage() = runTest {
        // Arrange
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user

        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.failure(RuntimeException("boom"))

        // Act
        val vm = VideoUploadsViewModel(authRepository = authRepo, videoUploadRepository = uploadsRepo)
        advanceUntilIdle()

        // Assert
        val s = vm.uiState.value
        assertFalse(s.refreshing)
        assertNotNull(s.statusMessage)
        assertTrue(s.statusMessage!!.startsWith("Failed to load uploads:"))

        coVerify(exactly = 1) { uploadsRepo.loadVideos("uid123") }
    }

    @Test
    fun saveEdit_guardClause_invalidDraft_doesNotCallRepository() = runTest {
        // Arrange: make init refresh harmless
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

        vm.editTitleChanged("") // invalid => canSave false

        // Act
        vm.saveEdit()
        advanceUntilIdle()

        // Assert
        coVerify(exactly = 0) { uploadsRepo.updateDance(any(), any(), any(), any()) }
        assertFalse(vm.uiState.value.saving)
        assertNotNull(vm.uiState.value.editDraft)
    }

    @Test
    fun saveEdit_success_callsUpdate_thenClosesDraft_andRefreshes() = runTest {
        // Arrange
        val user = mockk<UserProfile> { every { uid } returns "uid123" }
        coEvery { authRepo.getCurrentUserProfile() } returns user

        // init refresh
        coEvery { uploadsRepo.loadVideos("uid123") } returns Result.success(emptyList())

        // update
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

        // Act
        vm.saveEdit()
        advanceUntilIdle()

        // Assert
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

        // init refresh + refresh after save
        coVerify(atLeast = 2) { uploadsRepo.loadVideos("uid123") }
    }

    @Test
    fun confirmDelete_success_callsDelete_thenClearsDialog_andRefreshes() = runTest {
        // Arrange
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

        // Act
        vm.confirmDelete()
        advanceUntilIdle()

        // Assert
        coVerify(exactly = 1) { uploadsRepo.deleteDance("d1", "uid123") }

        val s = vm.uiState.value
        assertFalse(s.deleting)
        assertNull(s.deleteItem)
        assertEquals("Deleted", s.statusMessage)

        coVerify(atLeast = 2) { uploadsRepo.loadVideos("uid123") }
    }
}
