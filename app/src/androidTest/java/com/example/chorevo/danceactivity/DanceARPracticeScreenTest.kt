package com.example.chorevo.danceactivity

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.chorevo.danceactivity.ViewModel.AchievementViewModel
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.posedetection.PoseFrame
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTrack
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Before
import org.junit.Rule
import org.junit.Test


class DanceARPracticeScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Main repo / VM used by this screen
    private lateinit var repo: DanceLibraryRepository
    private lateinit var streakRepository: FirebaseStreakRepository
    private lateinit var leaderboardRepository: LeaderboardRepository
    private lateinit var vm: DanceLibraryListViewModel

    // New dependency now required by the screen
    private lateinit var achievementVm: AchievementViewModel

    // Shared test data
    private lateinit var item: DanceLibraryModel

    private var backClicks = 0

    @Before
    fun setUp() {
        repo = mockk()
        streakRepository = mockk(relaxed = true)
        leaderboardRepository = mockk(relaxed = true)

        coEvery { repo.listEnabled() } returns Result.success(emptyList())
        coEvery {
            repo.saveSessionAndUpload(
                session = any(),
                extraAnalytics = any(),
                uid = any()
            )
        } returns Result.success(Unit)

        vm = DanceLibraryListViewModel(
            repo,
            streakRepository,
            leaderboardRepository
        )

        achievementVm = AchievementViewModel(
             repository = mockk(relaxed = true),
             pointsRepository = mockk(relaxed = true),
             streakRepository = mockk(relaxed = true)
        )

        item = DanceLibraryModel(
            danceId = "d1",
            title = "Salsa Basics",
            educatorName = "Coach A",
            educatorUid = "uid123",
            danceType = "Salsa",
            description = "Intro",
            videoUrl = "https://example.com/video.mp4",
            enabled = true,
            template = null
        )

        backClicks = 0
    }

    private fun waitForTextExists(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun practiceScreen_showsMetricsLabels_andButtons_andBackWorks() {
        composeRule.setContent {
            DanceARPracticeScreen(
                item = item,
                onBack = { backClicks++ },
                onNavigateToProfile = {},
                vm = vm,
                achievementVm = achievementVm,
                uid = "uid123",
                testMode = true
                
            )
        }

        waitForTextExists("Reset")
        waitForTextExists("Submit")

        composeRule.onNodeWithText("Reset").assertIsDisplayed()
        composeRule.onNodeWithText("Submit").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertDoesNotExist()
        composeRule.onNodeWithText("Live Analytics").assertDoesNotExist()
    }

    @Test
    fun practiceScreen_showsThreeSecondCountdown_beforePracticeStarts() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            DanceARPracticeScreen(
                item = item,
                onBack = { backClicks++ },
                onNavigateToProfile = {},
                vm = vm,
                achievementVm = achievementVm,
                uid = "uid123",
                testMode = true
            )
        }

        waitForTextExists("Reset")
        composeRule.onNodeWithText("Reset").performClick()

        waitForTextExists("Start Practice")
        composeRule.onNodeWithText("Start Practice").performClick()

        composeRule.onNodeWithTag("practiceStartCountdown").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("practiceStartCountdown").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Reset").assertIsDisplayed()
        composeRule.onNodeWithText("Submit").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertDoesNotExist()
        composeRule.onNodeWithText("Live Analytics").assertDoesNotExist()
    }

    @Test
    fun practiceScreen_referenceDancerSelector_scrollsToLaterDancers() {
        val tracks = (1..12).map { index ->
            PoseTrack(
                trackId = "track_$index",
                label = "Dancer $index",
                frames = listOf(PoseFrame(tMs = 0L, landmarks = listOf(0f, 0f, 0f, 1f))),
                avgVisibility = 1f
            )
        }
        val multiDancerItem = item.copy(
            template = PoseTemplate(
                fps = 2,
                frames = tracks.first().frames,
                tracks = tracks,
                defaultTrackId = tracks.first().trackId
            )
        )

        composeRule.setContent {
            DanceARPracticeScreen(
                item = multiDancerItem,
                onBack = { backClicks++ },
                onNavigateToProfile = {},
                vm = vm,
                achievementVm = achievementVm,
                uid = "uid123",
                testMode = true
            )
        }

        composeRule.onNodeWithTag("dancerSelectorCard").assertIsDisplayed()
        composeRule.onNodeWithTag("dancerChip_track_12")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun practiceScreen_withTemplate_showsStepByStepModeControls() {
        val frames = List(12) { index ->
            PoseFrame(
                tMs = index * 1000L,
                landmarks = List(33 * 4) { 0.5f }
            )
        }

        val itemWithTemplate = item.copy(
            template = PoseTemplate(
                fps = 2,
                frames = frames
            )
        )

        composeRule.setContent {
            DanceARPracticeScreen(
                item = itemWithTemplate,
                onBack = { backClicks++ },
                onNavigateToProfile = {},
                vm = vm,
                achievementVm = achievementVm,
                uid = "uid123",
                testMode = true
            )
        }

        composeRule.onNodeWithTag("stepPracticeCard").assertIsDisplayed()
        composeRule.onNodeWithText("Full Routine").assertIsDisplayed()
        composeRule.onNodeWithText("Step-by-Step").assertIsDisplayed()
        composeRule.onNodeWithText("Step-by-Step").performClick()
        composeRule.onNodeWithText("Step 1 of 2").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
    }
}
