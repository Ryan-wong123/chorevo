package com.example.chorevo.danceactivity

import com.example.chorevo.danceactivity.Repository.AchievementRepository
import com.example.chorevo.danceactivity.Repository.SessionRewardsRepository
import com.example.chorevo.danceactivity.ViewModel.AchievementViewModel
import com.example.chorevo.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRewardsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    @Test
    fun parsesTheServerSummary_skippingUnknownBadges() {
        val r = sessionRewardsFrom(
            "s1",
            mapOf(
                "capped" to false,
                "sessionPoints" to 83L,
                "challengePoints" to 40L,
                "totalPointsBefore" to 480L,
                "totalPointsAfter" to 603L,
                "newBadges" to listOf("DEDICATED", "NOT_A_BADGE"),
                "completedChallenges" to listOf(
                    mapOf("id" to "focus_drill", "title" to "Focus Drill: Timing", "emoji" to "🎵", "rewardPoints" to 40L)
                ),
                "bonusUnlocked" to false,
                "bonusPoints" to 0L
            )
        )
        assertEquals(83, r.sessionPoints)
        assertEquals(603, r.totalPointsAfter)
        assertEquals(listOf(BadgeId.DEDICATED), r.newBadges)
        assertEquals("focus_drill", r.completedChallenges.single().id)
        assertEquals(40, r.completedChallenges.single().rewardPoints)
    }

    private fun vm(flow: MutableStateFlow<SessionRewards?>): AchievementViewModel {
        val badges = mockk<AchievementRepository>()
        coEvery { badges.getEarnedBadges("u1") } returns setOf(BadgeId.FIRST_STEPS, BadgeId.DEDICATED)
        val rewards = mockk<SessionRewardsRepository>()
        every { rewards.observe("u1", "s1") } returns flow
        return AchievementViewModel(badges, rewards)
    }

    @Test
    fun showsWhatTheServerAwarded_andPassesItOn() = runTest {
        val flow = MutableStateFlow<SessionRewards?>(null)
        val vm = vm(flow)
        var passedOn: SessionRewards? = null
        vm.awaitSessionRewards("u1", "s1") { passedOn = it }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showAchievementDialog) // still waiting for the server

        val r = SessionRewards(
            sessionId = "s1", sessionPoints = 83, totalPointsBefore = 480, totalPointsAfter = 563,
            newBadges = listOf(BadgeId.DEDICATED)
        )
        flow.value = r
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.showAchievementDialog)
        assertEquals(83, s.sessionPoints)
        assertEquals(563, s.totalPoints)
        assertEquals(listOf(BadgeId.DEDICATED), s.newlyUnlocked.map { it.id })
        assertTrue(s.leveledUp) // 480 (Mover) -> 563 (Dancer)
        assertFalse(s.rewardsPending)
        assertEquals(r, passedOn)
    }

    @Test
    fun withoutAResult_inTime_showsThePendingState() = runTest {
        val vm = vm(MutableStateFlow(null))
        var passedOn: SessionRewards? = null
        vm.awaitSessionRewards("u1", "s1") { passedOn = it }
        advanceTimeBy(21_000)
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.showAchievementDialog)
        assertTrue(s.rewardsPending)
        assertEquals(0, s.sessionPoints)
        assertNull(passedOn)
    }
}
