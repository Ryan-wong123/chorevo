package com.example.chorevo.danceactivity

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import com.example.chorevo.danceactivity.viewmodel.PracticeViewModel
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.chorevo.danceactivity.viewmodel.AchievementViewModel
import com.example.chorevo.danceactivity.repository.FirebaseStreakRepository
import com.example.chorevo.weeklychallenge.viewmodel.WeeklyChallengeViewModel
import com.example.chorevo.dancelibrarylist.viewmodel.DanceLibraryListViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.stubFeed
import com.example.chorevo.dancelibrarylist.repository.DanceLibraryRepository
import com.example.chorevo.leaderboard.repository.LeaderboardRepository
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
    private lateinit var weeklyChallengeVm: WeeklyChallengeViewModel
    private lateinit var practiceVm: PracticeViewModel
    // Drives PracticeViewModel's countdown and timer, so the test controls time.
    private lateinit var practiceScheduler: TestCoroutineScheduler

    // Shared test data
    private lateinit var item: DanceLibraryModel

    private var backClicks = 0

    @Before
    fun setUp() {
        repo = mockk()
        streakRepository = mockk(relaxed = true)
        leaderboardRepository = mockk(relaxed = true)

        repo.stubFeed(emptyList())
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
             rewardsRepository = mockk(relaxed = true)
        )

        weeklyChallengeVm = WeeklyChallengeViewModel(
            repository = mockk(relaxed = true)
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        practiceScheduler = TestCoroutineScheduler()
        practiceVm = PracticeViewModel(
            context,
            PersonalBestStore(context),
            timerDispatcher = StandardTestDispatcher(practiceScheduler)
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
                weeklyChallengeVm = weeklyChallengeVm,
                uid = "uid123",
                testMode = true,
                practiceVm = practiceVm
                
            )
        }

        waitForTextExists("Reset")
        waitForTextExists("Submit")

        composeRule.onNodeWithText("Reset").assertIsDisplayed()
        composeRule.onNodeWithText("Submit").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Back").performClick()
        assert(backClicks == 1)
        composeRule.onNodeWithText("Live Analytics").assertIsDisplayed()
        composeRule.onNodeWithText("Nice match. Keep the timing steady.").assertIsDisplayed()
    }

    @Test
    fun practiceScreen_showsThreeSecondCountdown_beforePracticeStarts() {
        composeRule.setContent {
            DanceARPracticeScreen(
                item = item,
                onBack = { backClicks++ },
                onNavigateToProfile = {},
                vm = vm,
                achievementVm = achievementVm,
                weeklyChallengeVm = weeklyChallengeVm,
                uid = "uid123",
                testMode = true,
                practiceVm = practiceVm
            )
        }

        waitForTextExists("Reset")
        composeRule.onNodeWithText("Reset").performClick()
        waitForTextExists("Start Practice")

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Start Practice").performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("practiceStartCountdown").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("practiceStartCountdown").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()

        // Run the ViewModel's 3-second countdown, then let the screen recompose.
        practiceScheduler.advanceTimeBy(3_100)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("practiceStartCountdown").assertCountEquals(0)
        composeRule.onNodeWithText("Reset").assertIsDisplayed()
        composeRule.onNodeWithText("Submit").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Live Analytics").assertIsDisplayed()
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
                weeklyChallengeVm = weeklyChallengeVm,
                uid = "uid123",
                testMode = true,
                practiceVm = practiceVm
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
                weeklyChallengeVm = weeklyChallengeVm,
                uid = "uid123",
                testMode = true,
                practiceVm = practiceVm
            )
        }

        composeRule.onNodeWithTag("stepPracticeCard").assertIsDisplayed()
        composeRule.onNodeWithText("Full Routine").assertIsDisplayed()
        composeRule.onNodeWithText("Step-by-Step").assertIsDisplayed()
        composeRule.onNodeWithText("Step-by-Step").performClick()
        composeRule.onAllNodesWithText("Step 1 of 2", substring = true).assertCountEquals(3)
        composeRule.onNodeWithText("Next").assertIsDisplayed()
    }
}
