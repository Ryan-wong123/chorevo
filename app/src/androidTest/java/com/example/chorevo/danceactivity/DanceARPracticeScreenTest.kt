package com.example.chorevo.danceactivity

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.chorevo.danceactivity.ViewModel.AchievementViewModel
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
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

        waitForTextExists("AR Practice: Salsa Basics")
        composeRule.onNodeWithText("AR Practice: Salsa Basics").assertIsDisplayed()

        waitForTextExists("Live Analytics")
        composeRule.onNodeWithText("Live Analytics").assertIsDisplayed()

        waitForTextExists("Reset")
        waitForTextExists("Submit")
        waitForTextExists("Back")

        composeRule.onNodeWithText("Reset").assertIsDisplayed()
        composeRule.onNodeWithText("Submit").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertIsDisplayed()

        composeRule.onNodeWithText("Back").performClick()
        assertEquals(1, backClicks)
    }
}
