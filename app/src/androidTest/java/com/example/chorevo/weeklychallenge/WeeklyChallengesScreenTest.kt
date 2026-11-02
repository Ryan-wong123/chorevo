package com.example.chorevo.weeklychallenge

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.weeklychallenge.repository.WeeklyChallengeRepository
import com.example.chorevo.weeklychallenge.viewmodel.WeeklyChallengeViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(AndroidJUnit4::class)
class WeeklyChallengesScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repo: WeeklyChallengeRepository
    private lateinit var vm: WeeklyChallengeViewModel

    private val focusDrill = WeeklyChallenge(
        id = "focus_drill",
        type = ChallengeType.FOCUS_DRILL,
        title = "Sharpen your timing",
        description = "Score 80% timing in 3 sessions",
        emoji = "🎯",
        targetCount = 3,
        progressCount = 1,
        rewardPoints = 25,
        focusMetric = ScoreMetric.TIMING,
        focusThreshold = 0.8
    )
    private val styleExplorer = WeeklyChallenge(
        id = "style_explorer",
        type = ChallengeType.STYLE_EXPLORER,
        title = "Try new styles",
        description = "Practise two different dance styles",
        emoji = "🧭",
        targetCount = 2,
        progressCount = 2,
        rewardPoints = 30,
        completed = true,
        completedAt = 1L
    )
    private val momentum = WeeklyChallenge(
        id = "momentum_goal",
        type = ChallengeType.MOMENTUM_GOAL,
        title = "Keep the momentum",
        description = "Finish 5 sessions this week",
        emoji = "🔥",
        targetCount = 5,
        progressCount = 0,
        rewardPoints = 40
    )

    private fun weekSet(
        challenges: List<WeeklyChallenge> = listOf(focusDrill, styleExplorer, momentum),
        allCompleted: Boolean = false,
        bonus: Int = 50,
        weekStart: String = "2026-10-26"
    ) = WeeklyChallengeSet(
        weekStart = weekStart,
        challenges = challenges,
        allCompleted = allCompleted,
        bonusRewardPoints = bonus
    )

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        coEvery { repo.getOrCreateCurrentWeek(any()) } returns weekSet()
        vm = WeeklyChallengeViewModel(repo)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun waitForTextExists(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setScreen(uid: String = "u1", onBack: () -> Unit = {}) {
        composeRule.setContent {
            WeeklyChallengesScreen(uid = uid, vm = vm, onBack = onBack)
        }
    }

    // ── Static chrome ────────────────────────────────────────────────────────

    @Test
    fun topBar_showsTitleAndBackButton() {
        setScreen()

        composeRule.onNodeWithText("Weekly Challenges").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun clickBack_invokesOnBack() {
        var backCount = 0
        setScreen(onBack = { backCount++ })

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun launch_loadsChallengesForGivenUid() {
        setScreen(uid = "uid-42")

        waitForTextExists("Sharpen your timing")
        coVerify(exactly = 1) { repo.getOrCreateCurrentWeek("uid-42") }
    }

    // ── Loaded content ───────────────────────────────────────────────────────

    @Test
    fun loaded_showsWeekLabelBuiltFromWeekStart() {
        setScreen()

        val fmt = DateTimeFormatter.ofPattern("MMM d")
        val start = LocalDate.parse("2026-10-26")
        val expected = "Week of ${start.format(fmt)} – ${start.plusDays(6).format(fmt)}"

        waitForTextExists(expected)
        composeRule.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test
    fun loaded_withInvalidWeekStart_fallsBackToThisWeekLabel() {
        coEvery { repo.getOrCreateCurrentWeek(any()) } returns weekSet(weekStart = "not-a-date")
        vm = WeeklyChallengeViewModel(repo)
        setScreen()

        waitForTextExists("Sharpen your timing")
        composeRule.onNodeWithText("This week").assertIsDisplayed()
    }

    @Test
    fun loaded_showsHeaderProgressAndBonusHint() {
        setScreen()

        waitForTextExists("1 of 3 complete")
        composeRule.onNodeWithText("1 of 3 complete").assertIsDisplayed()
        composeRule.onNodeWithText("Complete all three this week for a +50 pt bonus.").assertIsDisplayed()
        // The celebration emoji only shows once everything is complete.
        composeRule.onAllNodesWithText("🎉").assertCountEquals(0)
    }

    @Test
    fun loaded_showsChallengeCardsWithTitleDescriptionProgressAndReward() {
        setScreen()

        waitForTextExists("Sharpen your timing")
        composeRule.onNodeWithText("Sharpen your timing").assertIsDisplayed()
        composeRule.onNodeWithText("Score 80% timing in 3 sessions").assertIsDisplayed()
        composeRule.onNodeWithText("🎯").assertIsDisplayed()
        composeRule.onNodeWithText("1/3").assertIsDisplayed()
        composeRule.onNodeWithText("+25 pts").assertIsDisplayed()

        composeRule.onNodeWithText("Try new styles").assertIsDisplayed()
        composeRule.onNodeWithText("2/2").assertIsDisplayed()
        composeRule.onNodeWithText("+30 pts").assertIsDisplayed()
    }

    @Test
    fun loaded_lastChallenge_isReachableByScrolling() {
        setScreen()

        waitForTextExists("Sharpen your timing")
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("Keep the momentum"))
        composeRule.onNodeWithText("Keep the momentum").assertIsDisplayed()
        composeRule.onNodeWithText("0/5").assertIsDisplayed()
        composeRule.onNodeWithText("+40 pts").assertIsDisplayed()
    }

    @Test
    fun completedChallenge_showsCheckMark_onlyForCompletedOnes() {
        setScreen()

        waitForTextExists("Try new styles")
        // Only "Try new styles" is completed.
        composeRule.onAllNodesWithText("✓").assertCountEquals(1)
    }

    @Test
    fun allCompleted_showsCelebrationEmojiAndUnlockedBonusText() {
        val done = listOf(
            focusDrill.copy(progressCount = 3, completed = true),
            styleExplorer,
            momentum.copy(progressCount = 5, completed = true)
        )
        coEvery { repo.getOrCreateCurrentWeek(any()) } returns
            weekSet(challenges = done, allCompleted = true, bonus = 75)
        vm = WeeklyChallengeViewModel(repo)
        setScreen()

        waitForTextExists("3 of 3 complete")
        composeRule.onNodeWithText("🎉").assertIsDisplayed()
        composeRule.onNodeWithText("Bonus +75 pts unlocked for clearing all three!").assertIsDisplayed()
        composeRule.onAllNodesWithText("Complete all three this week for a +75 pt bonus.").assertCountEquals(0)
    }

    // ── Loading / empty / error ──────────────────────────────────────────────

    @Test
    fun whileLoading_showsShimmerAndNoEmptyState() {
        val gate = CompletableDeferred<WeeklyChallengeSet>()
        coEvery { repo.getOrCreateCurrentWeek(any()) } coAnswers { gate.await() }
        vm = WeeklyChallengeViewModel(repo)
        setScreen()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("shimmerList").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("shimmerList").assertIsDisplayed()
        composeRule.onAllNodesWithText("No challenges yet").assertCountEquals(0)
        composeRule.onAllNodesWithText("Couldn't load challenges").assertCountEquals(0)

        gate.complete(weekSet())
        waitForTextExists("Sharpen your timing")
        composeRule.onAllNodesWithTag("shimmerList").assertCountEquals(0)
    }

    @Test
    fun emptyWeek_showsNoChallengesYetState() {
        coEvery { repo.getOrCreateCurrentWeek(any()) } returns weekSet(challenges = emptyList(), bonus = 50)
        vm = WeeklyChallengeViewModel(repo)
        setScreen()

        waitForTextExists("No challenges yet")
        composeRule.onNodeWithText("No challenges yet").assertIsDisplayed()
        composeRule.onNodeWithText(
            "No challenges available yet — check back after your next session."
        ).assertIsDisplayed()
        composeRule.onNodeWithText("0 of 0 complete").assertIsDisplayed()
    }

    @Test
    fun loadFailure_showsCouldntLoadWithRepositoryMessage() {
        coEvery { repo.getOrCreateCurrentWeek(any()) } throws IllegalStateException("Network down")
        vm = WeeklyChallengeViewModel(repo)
        setScreen()

        waitForTextExists("Couldn't load challenges")
        composeRule.onNodeWithText("Couldn't load challenges").assertIsDisplayed()
        composeRule.onNodeWithText("Network down").assertIsDisplayed()
        composeRule.onAllNodesWithText("No challenges yet").assertCountEquals(0)
    }

    @Test
    fun loadFailure_withoutMessage_showsDefaultFailureText() {
        coEvery { repo.getOrCreateCurrentWeek(any()) } throws RuntimeException()
        vm = WeeklyChallengeViewModel(repo)
        setScreen()

        waitForTextExists("Couldn't load challenges")
        composeRule.onNodeWithText("Failed to load weekly challenges").assertIsDisplayed()
    }
}
