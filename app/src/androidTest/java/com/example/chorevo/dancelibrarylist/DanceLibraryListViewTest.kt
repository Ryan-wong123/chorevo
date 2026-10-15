package com.example.chorevo.dancelibrarylist

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.posedetection.PoseTemplate
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DanceLibraryListViewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repo: DanceLibraryRepository
    private lateinit var streakRepository: FirebaseStreakRepository
    private lateinit var leaderboardRepository: LeaderboardRepository
    private lateinit var vm: DanceLibraryListViewModel

    @Before
    fun setUp() {
        repo = mockk()
        streakRepository = mockk(relaxed = true)
        leaderboardRepository = mockk(relaxed = true)
        coEvery { repo.loadSocialData(any(), any()) } returns Result.success(0 to false)
        coEvery { repo.loadComments(any()) } returns Result.success(emptyList<DanceComment>() to null)
        vm = DanceLibraryListViewModel(repo, streakRepository, leaderboardRepository)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun waitForTextExists(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun emptyState_showsStatusText() {
        coEvery { repo.listEnabled() } returns Result.success(emptyList())

        composeRule.setContent {
            DanceLibraryListView(
                onPickDance = { },
                onBack = { },
                vm = vm,
                uid = "uid123"
            )
        }

        waitForTextExists("No dances available.")
        composeRule.onNodeWithText("No dances available.").assertIsDisplayed()

        // Since this screen should NOT have a Back button
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
    }

    @Test
    fun listShowsFirstItemTitleEducatorAndPractice() {
        val items = listOf(
            DanceLibraryModel(
                danceId = "d1",
                title = "Salsa Basics",
                educatorName = "Coach A",
                description = "Intro to salsa",
                videoUrl = "https://example.com/video1.mp4",
                enabled = true
            )
        )

        coEvery { repo.listEnabled() } returns Result.success(items)

        composeRule.setContent {
            DanceLibraryListView(
                onPickDance = { },
                onBack = { },
                vm = vm,
                uid = "uid123"
            )
        }

        waitForTextExists("Salsa Basics")

        composeRule.onNodeWithText("Salsa Basics").assertIsDisplayed()
        composeRule.onNodeWithText("by Coach A").assertIsDisplayed()
        composeRule.onNodeWithText("Practice").assertIsDisplayed()

        // Since this screen should NOT have a Back button
        composeRule.onAllNodesWithText("Back").assertCountEquals(0)
    }

    @Test
    fun swipeUp_movesToNextDancePage() {
        val items = listOf(
            DanceLibraryModel(
                danceId = "d1",
                title = "Salsa Basics",
                educatorName = "Coach A",
                description = "Intro to salsa",
                videoUrl = "https://example.com/video1.mp4",
                enabled = true
            ),
            DanceLibraryModel(
                danceId = "d2",
                title = "Hip Hop Groove",
                educatorName = "Coach B",
                description = "Groove and rhythm",
                videoUrl = "https://example.com/video2.mp4",
                enabled = true
            )
        )

        coEvery { repo.listEnabled() } returns Result.success(items)

        composeRule.setContent {
            DanceLibraryListView(
                onPickDance = { },
                onBack = { },
                vm = vm,
                uid = "uid123"
            )
        }

        waitForTextExists("Salsa Basics")
        composeRule.onNodeWithText("Salsa Basics").assertIsDisplayed()

        composeRule.onNodeWithTag("dancePager")
            .performTouchInput { swipeUp() }

        waitForTextExists("Hip Hop Groove")
        composeRule.onNodeWithText("Hip Hop Groove").assertIsDisplayed()
        composeRule.onNodeWithText("by Coach B").assertIsDisplayed()
    }

    @Test
    fun practiceButton_callsOnPickDance_withCorrectItem() {
        val items = listOf(
            DanceLibraryModel(
                danceId = "d1",
                title = "Salsa Basics",
                educatorName = "Coach A",
                description = "Intro to salsa",
                videoUrl = "https://example.com/video1.mp4",
                enabled = true
            ),
            DanceLibraryModel(
                danceId = "d2",
                title = "Hip Hop Groove",
                educatorName = "Coach B",
                description = "Groove and rhythm",
                videoUrl = "https://example.com/video2.mp4",
                enabled = true
            )
        )

        coEvery { repo.listEnabled() } returns Result.success(items)
        coEvery { repo.loadTemplate(any()) } returns Result.success(PoseTemplate())

        var picked: DanceLibraryModel? = null

        composeRule.setContent {
            DanceLibraryListView(
                onPickDance = { picked = it },
                onBack = { },
                vm = vm,
                uid = "uid123"
            )
        }

        waitForTextExists("Salsa Basics")

        composeRule.onNodeWithText("Practice").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { picked != null }
        assertEquals("d1", picked!!.danceId)
        assertEquals("Salsa Basics", picked!!.title)
    }

    // ── Discovery bar ─────────────────────────────────────────────────────

    private fun discoveryItems() = listOf(
        DanceLibraryModel(
            danceId = "d1", title = "Salsa Basics", educatorName = "Coach A",
            danceType = "Salsa", videoUrl = "https://example.com/v1.mp4", enabled = true
        ),
        DanceLibraryModel(
            danceId = "d2", title = "Hip Hop Groove", educatorName = "Coach B",
            danceType = "Hip Hop", videoUrl = "https://example.com/v2.mp4", enabled = true
        )
    )

    @Test
    fun discoveryBar_showsTabsAndCategoryChips() {
        coEvery { repo.listEnabled() } returns Result.success(discoveryItems())

        composeRule.setContent {
            DanceLibraryListView(onPickDance = { }, onBack = { }, vm = vm, uid = "uid123")
        }

        waitForTextExists("Salsa Basics")
        composeRule.onNodeWithText("For You").assertIsDisplayed()
        composeRule.onNodeWithText("Trending").assertIsDisplayed()
        composeRule.onNodeWithText("New").assertIsDisplayed()
        composeRule.onNodeWithTag("categoryChip_all").assertIsDisplayed()
        composeRule.onNodeWithTag("categoryChip_salsa").assertIsDisplayed()
        composeRule.onNodeWithTag("categoryChip_hiphop").assertIsDisplayed()
    }

    @Test
    fun categoryChip_filtersFeedToThatStyle() {
        coEvery { repo.listEnabled() } returns Result.success(discoveryItems())

        composeRule.setContent {
            DanceLibraryListView(onPickDance = { }, onBack = { }, vm = vm, uid = "uid123")
        }

        waitForTextExists("Salsa Basics")
        composeRule.onNodeWithTag("categoryChip_hiphop").performClick()

        waitForTextExists("Hip Hop Groove")
        composeRule.onNodeWithText("Hip Hop Groove").assertIsDisplayed()
        composeRule.onAllNodesWithText("Salsa Basics").assertCountEquals(0)
    }

    @Test
    fun searchWithNoMatch_showsEmptyState_andClearFiltersRestoresFeed() {
        coEvery { repo.listEnabled() } returns Result.success(discoveryItems())

        composeRule.setContent {
            DanceLibraryListView(onPickDance = { }, onBack = { }, vm = vm, uid = "uid123")
        }

        waitForTextExists("Salsa Basics")
        composeRule.onNodeWithTag("searchButton").performClick()
        composeRule.onNodeWithTag("searchField").performTextInput("ballet")

        waitForTextExists("No dances match")
        composeRule.onNodeWithTag("clearFilters").performClick()

        waitForTextExists("Salsa Basics")
        composeRule.onNodeWithText("Salsa Basics").assertIsDisplayed()
    }
}
