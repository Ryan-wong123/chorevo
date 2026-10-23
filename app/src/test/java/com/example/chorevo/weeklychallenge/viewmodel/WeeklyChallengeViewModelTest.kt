package com.example.chorevo.weeklychallenge.viewmodel

import com.example.chorevo.danceactivity.CompletedChallenge
import com.example.chorevo.danceactivity.SessionRewards
import com.example.chorevo.testing.MainDispatcherRule
import com.example.chorevo.weeklychallenge.ChallengeType
import com.example.chorevo.weeklychallenge.repository.WeeklyChallengeRepository
import com.example.chorevo.weeklychallenge.WeeklyChallenge
import com.example.chorevo.weeklychallenge.WeeklyChallengeSet
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

    // ── onSessionRewards (results computed by the server) ─────────────────────

    private fun rewards(vararg completed: CompletedChallenge, bonus: Boolean = false) = SessionRewards(
        sessionId = "s1",
        sessionPoints = 80,
        challengePoints = completed.sumOf { it.rewardPoints } + if (bonus) 50 else 0,
        completedChallenges = completed.toList(),
        bonusUnlocked = bonus,
        bonusPoints = if (bonus) 50 else 0
    )

    @Test
    fun onSessionRewards_showsCelebration_forChallengesTheServerCompleted() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        val set = WeeklyChallengeSet(weekStart = "2026-06-15", challenges = listOf(sampleChallenge(completed = true)))
        coEvery { repo.getOrCreateCurrentWeek("uid1") } returns set

        val vm = WeeklyChallengeViewModel(repo)
        vm.onSessionRewards("uid1", rewards(CompletedChallenge("focus_drill", "Focus Drill: Alignment", "🎯", 40)))

        val state = vm.uiState.value
        assertTrue(state.showCelebrationDialog)
        assertEquals(listOf("focus_drill"), state.newlyCompleted.map { it.id })
        assertEquals(ChallengeType.FOCUS_DRILL, state.newlyCompleted.single().type)
        assertEquals(40, state.newlyCompleted.single().rewardPoints)
        assertFalse(state.bonusNewlyUnlocked)
        // The week is reloaded from the server's copy.
        assertEquals(set, state.challengeSet)
    }

    @Test
    fun onSessionRewards_withNothingCompleted_doesNotShowCelebration() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        coEvery { repo.getOrCreateCurrentWeek("uid1") } returns WeeklyChallengeSet(weekStart = "2026-06-15")

        val vm = WeeklyChallengeViewModel(repo)
        vm.onSessionRewards("uid1", rewards())

        assertFalse(vm.uiState.value.showCelebrationDialog)
    }

    // ── dismissCelebrationDialog / clearError ───────────────────────────────

    @Test
    fun dismissCelebrationDialog_resetsCelebrationState() = runTest {
        val repo = mockk<WeeklyChallengeRepository>()
        coEvery { repo.getOrCreateCurrentWeek("uid1") } returns WeeklyChallengeSet(weekStart = "2026-06-15")

        val vm = WeeklyChallengeViewModel(repo)
        vm.onSessionRewards("uid1", rewards(CompletedChallenge("momentum_goal", "Momentum Goal", "🔥", 30), bonus = true))
        assertTrue(vm.uiState.value.showCelebrationDialog)
        assertTrue(vm.uiState.value.bonusNewlyUnlocked)

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
