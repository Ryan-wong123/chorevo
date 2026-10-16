package com.example.chorevo.core

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import com.example.chorevo.core.ViewModel.AppViewModel
import com.example.chorevo.core.ViewModel.Screen
import com.example.chorevo.core.ViewModel.ThemeMode
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.firebase.AutoRetryUploaderService
import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.notification.NotificationPreferencesDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var authRepository: FirebaseAuthRepository
    private lateinit var retryUploader: AutoRetryUploaderService
    private lateinit var settings: NotificationPreferencesDataStore
    private lateinit var danceLibraryRepository: DanceLibraryRepository
    private lateinit var vm: AppViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        alarmManager = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        retryUploader = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        danceLibraryRepository = mockk(relaxed = true)

        // ReminderScheduler is constructed in AppViewModel body — provide AlarmManager
        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager

        // Default: no signed-in user
        coEvery { authRepository.getCurrentUserProfile() } returns null
        every { settings.pushEnabledFlow } returns flowOf(false)
        every { settings.notificationTimeFlow } returns flowOf(9 to 0)
        every { settings.themeModeFlow } returns flowOf(null)
        every { settings.onboardingSeenFlow } returns flowOf(false)

        vm = AppViewModel(context, authRepository, retryUploader, settings, danceLibraryRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeProfile(
        uid: String = "uid1",
        displayName: String? = "Alice",
        email: String? = "alice@example.com"
    ) = UserProfile(uid = uid, email = email, displayName = displayName)

    private fun makeDanceItem(
        danceId: String = "d1",
        title: String = "Salsa Basics"
    ) = DanceLibraryModel(
        danceId = danceId,
        title = title,
        educatorName = "Coach A",
        description = "Intro dance",
        videoUrl = "https://example.com/$danceId.mp4",
        enabled = true
    )

    // ── Init: auth check ──────────────────────────────────────────────────────

    @Test
    fun init_withNullProfile_setsDidAuthCheck_andScreenToAuth() = runTest {
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.didAuthCheck)
        assertEquals(Screen.AUTH, state.screen)
        assertNull(state.profile)
    }

    @Test
    fun init_withProfile_setsScreenToHome_andStoresProfile() = runTest {
        val profile = makeProfile()
        coEvery { authRepository.getCurrentUserProfile() } returns profile

        val newVm = AppViewModel(context, authRepository, retryUploader, settings, danceLibraryRepository)
        advanceUntilIdle()

        val state = newVm.uiState.value
        assertTrue(state.didAuthCheck)
        assertEquals(Screen.HOME, state.screen)
        assertEquals(profile, state.profile)
    }

    // ── onAuthed ──────────────────────────────────────────────────────────────

    @Test
    fun onAuthed_setsScreenToHome_andStoresProfile() {
        val profile = makeProfile()

        vm.onAuthed(profile)

        assertEquals(Screen.HOME, vm.uiState.value.screen)
        assertEquals(profile, vm.uiState.value.profile)
    }

    @Test
    fun onAuthed_overwritesPreviousProfile() {
        vm.onAuthed(makeProfile(uid = "uid1", displayName = "Alice"))
        val newProfile = makeProfile(uid = "uid2", displayName = "Bob")

        vm.onAuthed(newProfile)

        assertEquals(newProfile, vm.uiState.value.profile)
        assertEquals(Screen.HOME, vm.uiState.value.screen)
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    fun logout_clearsProfile_andSetsScreenToAuth() {
        vm.onAuthed(makeProfile())

        vm.logout()

        assertEquals(Screen.AUTH, vm.uiState.value.screen)
        assertNull(vm.uiState.value.profile)
    }

    @Test
    fun logout_clearsSelectedDanceItem() {
        vm.onAuthed(makeProfile())
        vm.pickDance(makeDanceItem())

        vm.logout()

        assertNull(vm.uiState.value.selectedDanceItem)
        assertNull(vm.uiState.value.profile)
    }

    @Test
    fun logout_fromAnyScreen_alwaysGoesToAuth() {
        vm.onAuthed(makeProfile())
        vm.openLeaderboard()
        assertEquals(Screen.LEADERBOARD, vm.uiState.value.screen)

        vm.logout()

        assertEquals(Screen.AUTH, vm.uiState.value.screen)
    }

    // ── pickDance ─────────────────────────────────────────────────────────────

    @Test
    fun pickDance_setsScreenToDancePractice_andStoresItem() {
        val item = makeDanceItem()

        vm.pickDance(item)

        assertEquals(Screen.DANCE_PRACTICE, vm.uiState.value.screen)
        assertEquals(item, vm.uiState.value.selectedDanceItem)
    }

    @Test
    fun pickDance_replacesAnyPreviouslySelectedItem() {
        vm.pickDance(makeDanceItem(danceId = "d1", title = "Salsa"))
        val newItem = makeDanceItem(danceId = "d2", title = "Hip Hop")

        vm.pickDance(newItem)

        assertEquals(newItem, vm.uiState.value.selectedDanceItem)
    }

    // ── backToHome ────────────────────────────────────────────────────────────

    @Test
    fun backToHome_setsScreenToHome_andClearsDanceItem() {
        vm.pickDance(makeDanceItem())

        vm.backToHome()

        assertEquals(Screen.HOME, vm.uiState.value.screen)
        assertNull(vm.uiState.value.selectedDanceItem)
    }

    @Test
    fun backToHome_fromUpload_goesToHome() {
        vm.onAuthed(makeProfile())
        vm.openUpload()

        vm.backToHome()

        assertEquals(Screen.HOME, vm.uiState.value.screen)
    }

    // ── openUpload ────────────────────────────────────────────────────────────

    @Test
    fun openUpload_setsScreenToUpload() {
        vm.openUpload()
        assertEquals(Screen.UPLOAD, vm.uiState.value.screen)
    }

    // ── openLeaderboard ───────────────────────────────────────────────────────

    @Test
    fun openLeaderboard_setsScreenToLeaderboard() {
        vm.openLeaderboard()
        assertEquals(Screen.LEADERBOARD, vm.uiState.value.screen)
    }

    // ── openProfile ───────────────────────────────────────────────────────────

    @Test
    fun openProfile_setsScreenToProfile() {
        vm.openProfile()
        assertEquals(Screen.PROFILE, vm.uiState.value.screen)
    }

    // ── openSettings ──────────────────────────────────────────────────────────

    @Test
    fun openSettings_setsScreenToSettings() {
        vm.openSettings()
        assertEquals(Screen.SETTINGS, vm.uiState.value.screen)
    }

    // ── Navigation sequences ──────────────────────────────────────────────────

    @Test
    fun authThenPickDanceThenBackToHome_restoresHomeWithNoItem() {
        vm.onAuthed(makeProfile())
        vm.pickDance(makeDanceItem())
        assertEquals(Screen.DANCE_PRACTICE, vm.uiState.value.screen)

        vm.backToHome()

        assertEquals(Screen.HOME, vm.uiState.value.screen)
        assertNull(vm.uiState.value.selectedDanceItem)
    }

    @Test
    fun multipleTabSwitches_lastOneWins() {
        vm.onAuthed(makeProfile())
        vm.openUpload()
        vm.openLeaderboard()
        vm.openProfile()
        vm.openLeaderboard()

        assertEquals(Screen.LEADERBOARD, vm.uiState.value.screen)
    }

    @Test
    fun backToHome_doesNotAffectProfile() {
        val profile = makeProfile()
        vm.onAuthed(profile)
        vm.openUpload()

        vm.backToHome()

        assertEquals(profile, vm.uiState.value.profile)
        assertEquals(Screen.HOME, vm.uiState.value.screen)
    }

    // ── Share links ───────────────────────────────────────────────────────────

    @Test
    fun openDanceById_whenSignedIn_showsSharedVideoInHomeFeed() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        val dance = makeDanceItem(danceId = "shared1")
        coEvery { authRepository.getCurrentUserProfile() } returns makeProfile()
        coEvery { danceLibraryRepository.getDanceById("shared1", "uid1") } returns Result.success(dance)
        vm = AppViewModel(context, authRepository, retryUploader, settings, danceLibraryRepository)
        advanceUntilIdle()

        vm.openDanceById("shared1")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(Screen.HOME, state.screen)
        assertEquals(dance, state.sharedDance)
        assertNull(state.selectedDanceItem)
    }

    @Test
    fun backToHome_clearsSharedDance() = runTest {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        coEvery { authRepository.getCurrentUserProfile() } returns makeProfile()
        coEvery { danceLibraryRepository.getDanceById(any(), any()) } returns Result.success(makeDanceItem())
        vm = AppViewModel(context, authRepository, retryUploader, settings, danceLibraryRepository)
        advanceUntilIdle()
        vm.openDanceById("d1")
        advanceUntilIdle()

        vm.backToHome()

        assertNull(vm.uiState.value.sharedDance)
    }

    // ── Group invites (QR codes / links) ──────────────────────────────────────

    @Test
    fun openGroupInvite_whenSignedIn_opensGroupsWithTheCode() = runTest {
        coEvery { authRepository.getCurrentUserProfile() } returns makeProfile()
        vm = AppViewModel(context, authRepository, retryUploader, settings, danceLibraryRepository)
        advanceUntilIdle()

        vm.openGroupInvite("ABCD2345")

        assertEquals(Screen.COMMUNITY, vm.uiState.value.screen)
        assertEquals("ABCD2345", vm.uiState.value.pendingGroupInvite)
        vm.clearGroupInvite()
        assertNull(vm.uiState.value.pendingGroupInvite)
    }

    @Test
    fun openGroupInvite_whileSignedOut_waitsForSignIn() = runTest {
        advanceUntilIdle()

        vm.openGroupInvite("ABCD2345")
        assertEquals(Screen.AUTH, vm.uiState.value.screen)
        assertNull(vm.uiState.value.pendingGroupInvite)

        vm.onAuthed(makeProfile())
        assertEquals(Screen.COMMUNITY, vm.uiState.value.screen)
        assertEquals("ABCD2345", vm.uiState.value.pendingGroupInvite)
    }

    // ── navigateBack (system back gesture) ────────────────────────────────────

    @Test
    fun navigateBack_fromPractice_goesHome_andClearsDance() {
        vm.onAuthed(makeProfile())
        vm.pickDance(makeDanceItem())

        assertTrue(vm.navigateBack())

        assertEquals(Screen.HOME, vm.uiState.value.screen)
        assertNull(vm.uiState.value.selectedDanceItem)
    }

    @Test
    fun navigateBack_fromTabs_goesHome() {
        vm.onAuthed(makeProfile())
        listOf<() -> Unit>(vm::openUpload, vm::openCommunity, vm::openLeaderboard, vm::openProfile).forEach { open ->
            open()
            assertTrue(vm.navigateBack())
            assertEquals(Screen.HOME, vm.uiState.value.screen)
        }
    }

    @Test
    fun navigateBack_fromSettingsAndChallenges_goesToProfile() {
        vm.onAuthed(makeProfile())

        vm.openSettings()
        assertTrue(vm.navigateBack())
        assertEquals(Screen.PROFILE, vm.uiState.value.screen)

        vm.openWeeklyChallenges()
        assertTrue(vm.navigateBack())
        assertEquals(Screen.PROFILE, vm.uiState.value.screen)
    }

    @Test
    fun navigateBack_onHomeOrAuth_returnsFalse_soSystemCanExit() {
        vm.onAuthed(makeProfile())
        assertFalse(vm.navigateBack())
        assertEquals(Screen.HOME, vm.uiState.value.screen)

        vm.logout()
        assertFalse(vm.navigateBack())
        assertEquals(Screen.AUTH, vm.uiState.value.screen)
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    @Test
    fun themeMode_defaultsToDark_whenNothingSaved() = runTest {
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, vm.uiState.value.themeMode)
    }

    @Test
    fun themeMode_loadsSavedChoice() = runTest {
        every { settings.themeModeFlow } returns flowOf("light")

        val newVm = AppViewModel(context, authRepository, retryUploader, settings, danceLibraryRepository)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, newVm.uiState.value.themeMode)
    }

    @Test
    fun setThemeMode_updatesState_andSaves() = runTest {
        vm.setThemeMode(ThemeMode.SYSTEM)
        advanceUntilIdle()

        assertEquals(ThemeMode.SYSTEM, vm.uiState.value.themeMode)
        coVerify { settings.setThemeMode("system") }
    }

    @Test
    fun themeMode_fromKey_unknownFallsBackToDark() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromKey("purple"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey("light"))
    }

    // ── Onboarding ────────────────────────────────────────────────────────────

    @Test
    fun onboarding_notSeen_isLoadedAsFalse() = runTest {
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.onboardingSeen)
    }

    @Test
    fun completeOnboarding_marksSeen_andSaves() = runTest {
        advanceUntilIdle()

        vm.completeOnboarding()
        advanceUntilIdle()

        assertEquals(true, vm.uiState.value.onboardingSeen)
        coVerify { settings.setOnboardingSeen() }
    }
}
