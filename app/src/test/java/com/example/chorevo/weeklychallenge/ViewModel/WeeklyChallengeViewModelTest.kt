package com.example.chorevo.weeklychallenge.ViewModel

import com.example.chorevo.testing.MainDispatcherRule
import com.example.chorevo.weeklychallenge.ChallengeType
import com.example.chorevo.weeklychallenge.Repository.WeeklyChallengeRepository
import com.example.chorevo.weeklychallenge.WeeklyChallenge
import com.example.chorevo.weeklychallenge.WeeklyChallengeSet
import com.example.chorevo.weeklychallenge.WeeklyChallengeUpdateResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyChallengeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun sampleChallenge(
        id: String = "focus_drill",
        type: ChallengeType = ChallengeType.FOCUS_DRILL,
        completed: Boolean = false,
        rewardPoints: Int = 40
    ) = WeeklyChallenge(
        id = id,
        type = type,
        title = "Focus Drill: Alignment",
        description = "Score 60%+ on Alignment in 2 sessions this week.",
        emoji = "🎯",
        targetCount = 2,
        progressCount = if (completed) 2 else 0,
        rewardPoints = rewardPoints,
        completed = completed
    )

    // ── loadForWeek ───────────────────────────────────────────────────────────

    @Test
    fun loadForWeek_success_updatesStateWithChallengeSet() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        val set = WeeklyChallengeSet(
            weekStart = "2026-06-15",
            challenges = listOf(sampleChallenge())
        )
        coEvery { repo.getOrCreateCurrentWeek("uid1") } returns set

        val vm = WeeklyChallengeViewModel(repo)
        vm.loadForWeek("uid1")

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(set, state.challengeSet)
        assertNull(state.errorMessage)
    }

    @Test
    fun loadForWeek_failure_setsErrorMessage_andClearsLoading() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        coEvery { repo.getOrCreateCurrentWeek("uid1") } throws RuntimeException("boom")

        val vm = WeeklyChallengeViewModel(repo)
        vm.loadForWeek("uid1")

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals("boom", state.errorMessage)
    }

    // ── recordCompletedSession ───────────────────────────────────────────────

    @Test
    fun recordCompletedSession_success_showsCelebrationDialog_whenChallengeCompletes() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        val completedChallenge = sampleChallenge(completed = true)
        val updatedSet = WeeklyChallengeSet(
            weekStart = "2026-06-15",
            challenges = listOf(completedChallenge)
        )
        val updateResult = WeeklyChallengeUpdateResult(
            challengeSet = updatedSet,
            newlyCompleted = listOf(completedChallenge),
            bonusNewlyUnlocked = false
        )

        coEvery {
            repo.recordCompletedSession(
                uid = "uid1",
                danceType = "Salsa",
                alignmentScore = 0.9,
                timingConsistencyScore = 0.9,
                smoothnessScore = 0.9
            )
        } returns updateResult

        val vm = WeeklyChallengeViewModel(repo)
        vm.recordCompletedSession(
            uid = "uid1",
            danceType = "Salsa",
            alignmentScore = 0.9,
            timingConsistencyScore = 0.9,
            smoothnessScore = 0.9
        )

        val state = vm.uiState.value
        assertTrue(state.showCelebrationDialog)
        assertEquals(listOf(completedChallenge), state.newlyCompleted)
        assertFalse(state.bonusNewlyUnlocked)
        assertEquals(updatedSet, state.challengeSet)
    }

    @Test
    fun recordCompletedSession_withNoNewCompletions_doesNotShowCelebrationDialog() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        val inProgressChallenge = sampleChallenge(completed = false)
        val updateResult = WeeklyChallengeUpdateResult(
            challengeSet = WeeklyChallengeSet(
                weekStart = "2026-06-15",
                challenges = listOf(inProgressChallenge)
            ),
            newlyCompleted = emptyList(),
            bonusNewlyUnlocked = false
        )

        coEvery {
            repo.recordCompletedSession(any(), any(), any(), any(), any())
        } returns updateResult

        val vm = WeeklyChallengeViewModel(repo)
        vm.recordCompletedSession("uid1", "Salsa", 0.5, 0.5, 0.5)

        assertFalse(vm.uiState.value.showCelebrationDialog)
    }

    @Test
    fun recordCompletedSession_failure_setsErrorMessage() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        coEvery {
            repo.recordCompletedSession(any(), any(), any(), any(), any())
        } throws RuntimeException("network down")

        val vm = WeeklyChallengeViewModel(repo)
        vm.recordCompletedSession("uid1", "Salsa", 0.5, 0.5, 0.5)

        assertEquals("network down", vm.uiState.value.errorMessage)
    }

    // ── dismissCelebrationDialog / clearError ───────────────────────────────

    @Test
    fun dismissCelebrationDialog_resetsCelebrationState() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        val completedChallenge = sampleChallenge(completed = true)
        val updateResult = WeeklyChallengeUpdateResult(
            challengeSet = WeeklyChallengeSet(
                weekStart = "2026-06-15",
                challenges = listOf(completedChallenge)
            ),
            newlyCompleted = listOf(completedChallenge),
            bonusNewlyUnlocked = true
        )
        coEvery {
            repo.recordCompletedSession(any(), any(), any(), any(), any())
        } returns updateResult

        val vm = WeeklyChallengeViewModel(repo)
        vm.recordCompletedSession("uid1", "Salsa", 0.9, 0.9, 0.9)
        assertTrue(vm.uiState.value.showCelebrationDialog)

        vm.dismissCelebrationDialog()

        val state = vm.uiState.value
        assertFalse(state.showCelebrationDialog)
        assertTrue(state.newlyCompleted.isEmpty())
        assertFalse(state.bonusNewlyUnlocked)
    }

    @Test
    fun clearError_resetsErrorMessageToNull() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        coEvery { repo.getOrCreateCurrentWeek(any()) } throws RuntimeException("boom")

        val vm = WeeklyChallengeViewModel(repo)
        vm.loadForWeek("uid1")
        assertEquals("boom", vm.uiState.value.errorMessage)

        vm.clearError()
        assertNull(vm.uiState.value.errorMessage)
    }
}
