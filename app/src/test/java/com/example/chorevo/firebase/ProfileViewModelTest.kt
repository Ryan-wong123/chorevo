package com.example.chorevo.firebase

import com.example.chorevo.danceactivity.repository.FirebaseStreakRepository
import com.example.chorevo.danceactivity.repository.PointsRepository
import com.example.chorevo.danceactivity.repository.PointsSnapshot
import com.example.chorevo.danceactivity.repository.WeeklyStreak
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.repository.DanceLibraryRepository
import com.example.chorevo.firebase.viewmodel.ProfileUiState
import com.example.chorevo.firebase.viewmodel.ProfileViewModel
import com.example.chorevo.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var streaks: FirebaseStreakRepository
    private lateinit var points: PointsRepository
    private lateinit var library: DanceLibraryRepository
    private lateinit var vm: ProfileViewModel

    private val streak = WeeklyStreak(
        weekStart = "2026-10-26",
        daysMask = 0b0000101,
        totalStreakActivities = 12,
        lastActivityDate = "2026-10-28"
    )
    private val snapshot = PointsSnapshot(totalPoints = 340, lastSessionPoints = 25)
    private val liked = listOf(DanceLibraryModel(danceId = "d1", title = "Salsa"), DanceLibraryModel(danceId = "d2"))

    @Before
    fun setUp() {
        streaks = mockk()
        points = mockk()
        library = mockk()
        coEvery { streaks.getWeeklyStreak("u1", any()) } returns streak
        coEvery { points.getPoints("u1") } returns snapshot
        coEvery { library.getLikedDances("u1") } returns Result.success(liked)
        vm = ProfileViewModel(streaks, points, library)
    }

    @Test
    fun initialState_isEmptyAndIdle() {
        assertEquals(ProfileUiState(), vm.uiState.value)
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.isLoadingLikes)
    }

    @Test
    fun refresh_populatesStreakPointsAndLikedDances() = runTest {
        vm.refresh("u1")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(streak, s.streak)
        assertEquals(snapshot, s.points)
        assertEquals(liked, s.likedDances)
        assertFalse(s.isLoading)
        assertFalse(s.isLoadingLikes)
        assertNull(s.statusMessage)
    }

    @Test
    fun refresh_showsLoadingUntilStreakAndPointsArrive() = runTest {
        val gate = CompletableDeferred<PointsSnapshot>()
        coEvery { points.getPoints("u1") } coAnswers { gate.await() }

        vm.refresh("u1")
        runCurrent()

        assertTrue(vm.uiState.value.isLoading)
        // Liked dances load independently of the streak/points call.
        assertEquals(liked, vm.uiState.value.likedDances)

        gate.complete(snapshot)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(snapshot, vm.uiState.value.points)
    }

    @Test
    fun refresh_streakFailure_fallsBackToEmptyStreakButKeepsPoints() = runTest {
        coEvery { streaks.getWeeklyStreak("u1", any()) } throws RuntimeException("offline")

        vm.refresh("u1")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(WeeklyStreak(), s.streak)
        assertEquals(snapshot, s.points)
        assertFalse(s.isLoading)
    }

    @Test
    fun refresh_pointsFailure_fallsBackToEmptyPointsButKeepsStreak() = runTest {
        coEvery { points.getPoints("u1") } throws RuntimeException("offline")

        vm.refresh("u1")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(PointsSnapshot(), s.points)
        assertEquals(streak, s.streak)
        assertFalse(s.isLoading)
    }

    @Test
    fun refresh_likedDancesFailure_yieldsEmptyListAndStopsLoading() = runTest {
        coEvery { library.getLikedDances("u1") } returns Result.failure(RuntimeException("denied"))

        vm.refresh("u1")
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.likedDances.isEmpty())
        assertFalse(s.isLoadingLikes)
        // Streak and points still load when likes fail.
        assertEquals(streak, s.streak)
    }

    @Test
    fun loadLikedDances_setsLoadingFlagThenPublishesResult() = runTest {
        val gate = CompletableDeferred<Result<List<DanceLibraryModel>>>()
        coEvery { library.getLikedDances("u1") } coAnswers { gate.await() }

        vm.loadLikedDances("u1")
        runCurrent()
        assertTrue(vm.uiState.value.isLoadingLikes)

        gate.complete(Result.success(liked))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoadingLikes)
        assertEquals(liked, vm.uiState.value.likedDances)
    }

    @Test
    fun loadLikedDances_doesNotTouchStreakOrPoints() = runTest {
        vm.loadLikedDances("u1")
        advanceUntilIdle()

        assertEquals(WeeklyStreak(), vm.uiState.value.streak)
        assertEquals(PointsSnapshot(), vm.uiState.value.points)
        coVerify(exactly = 0) { streaks.getWeeklyStreak(any(), any()) }
        coVerify(exactly = 0) { points.getPoints(any()) }
    }

    @Test
    fun loadLikedDances_reloadReplacesPreviousList() = runTest {
        vm.loadLikedDances("u1")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.likedDances.size)

        coEvery { library.getLikedDances("u1") } returns Result.success(listOf(DanceLibraryModel(danceId = "only")))
        vm.loadLikedDances("u1")
        advanceUntilIdle()

        assertEquals(listOf("only"), vm.uiState.value.likedDances.map { it.danceId })
    }
}
