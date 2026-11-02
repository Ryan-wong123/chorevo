package com.example.chorevo.firebase

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.analytics.repository.AnalyticsRepository
import com.example.chorevo.analytics.viewmodel.AnalyticsDashboardViewModel
import com.example.chorevo.danceactivity.repository.FirebaseStreakRepository
import com.example.chorevo.danceactivity.repository.PointsRepository
import com.example.chorevo.danceactivity.repository.PointsSnapshot
import com.example.chorevo.danceactivity.repository.WeeklyStreak
import com.example.chorevo.dancelibrarylist.repository.DanceLibraryRepository
import com.example.chorevo.dancelibrarylist.viewmodel.DanceLibraryListViewModel
import com.example.chorevo.firebase.repository.FirebaseAuthRepository
import com.example.chorevo.firebase.viewmodel.ProfileViewModel
import com.example.chorevo.leaderboard.repository.LeaderboardRepository
import com.example.chorevo.posedetection.VideoPoseTemplateGenerator
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import com.example.chorevo.videouploads.viewmodel.VideoUploadsViewModel
import com.example.chorevo.weeklychallenge.ChallengeType
import com.example.chorevo.weeklychallenge.WeeklyChallenge
import com.example.chorevo.weeklychallenge.WeeklyChallengeSet
import com.example.chorevo.weeklychallenge.repository.WeeklyChallengeRepository
import com.example.chorevo.weeklychallenge.viewmodel.WeeklyChallengeViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserProfileScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var streakRepo: FirebaseStreakRepository
    private lateinit var pointsRepo: PointsRepository
    private lateinit var danceRepo: DanceLibraryRepository
    private lateinit var authRepo: FirebaseAuthRepository
    private lateinit var uploadsRepo: VideoUploadsRepository
    private lateinit var challengeRepo: WeeklyChallengeRepository

    private lateinit var profileVm: ProfileViewModel
    private lateinit var analyticsVm: AnalyticsDashboardViewModel
    private lateinit var uploadsVm: VideoUploadsViewModel
    private lateinit var danceVm: DanceLibraryListViewModel
    private lateinit var challengeVm: WeeklyChallengeViewModel

    private val alice = UserProfile(uid = "u1", email = "alice@example.com", displayName = "Alice Tan")

    @Before
    fun setUp() {
        streakRepo = mockk(relaxed = true)
        pointsRepo = mockk(relaxed = true)
        danceRepo = mockk(relaxed = true)
        authRepo = mockk(relaxed = true)
        uploadsRepo = mockk(relaxed = true)
        challengeRepo = mockk(relaxed = true)

        coEvery { streakRepo.getWeeklyStreak(any(), any()) } returns WeeklyStreak()
        coEvery { pointsRepo.getPoints(any()) } returns PointsSnapshot()
        coEvery { danceRepo.getLikedDances(any()) } returns Result.success(emptyList())
        coEvery { authRepo.getCurrentUserProfile() } returns null
        coEvery { challengeRepo.getOrCreateCurrentWeek(any()) } returns WeeklyChallengeSet()

        buildViewModels()
    }

    private fun buildViewModels() {
        profileVm = ProfileViewModel(streakRepo, pointsRepo, danceRepo)
        analyticsVm = AnalyticsDashboardViewModel(mockk<AnalyticsRepository>(relaxed = true))
        uploadsVm = VideoUploadsViewModel(
            authRepository = authRepo,
            videoUploadRepository = uploadsRepo,
            templateGenerator = mockk<VideoPoseTemplateGenerator>(relaxed = true)
        )
        danceVm = DanceLibraryListViewModel(
            danceRepo,
            mockk<FirebaseStreakRepository>(relaxed = true),
            mockk<LeaderboardRepository>(relaxed = true)
        )
        challengeVm = WeeklyChallengeViewModel(challengeRepo)
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

    /** The profile is one long LazyColumn; items below the fold aren't composed until scrolled to. */
    private fun scrollToText(text: String) {
        composeRule.onAllNodes(hasScrollAction()).onFirst()
            .performScrollToNode(hasText(text))
    }

    private fun setScreen(
        profile: UserProfile = alice,
        onLogout: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onOpenWeeklyChallenges: () -> Unit = {}
    ) {
        composeRule.setContent {
            UserProfileScreen(
                profile = profile,
                onLogout = onLogout,
                onOpenSettings = onOpenSettings,
                onOpenWeeklyChallenges = onOpenWeeklyChallenges,
                analyticsVm = analyticsVm,
                videoUploadsVm = uploadsVm,
                profileVm = profileVm,
                danceLibraryVm = danceVm,
                weeklyChallengeVm = challengeVm
            )
        }
    }

    // ── Header ───────────────────────────────────────────────────────────────

    @Test
    fun header_showsNameEmailSettingsAndLogout() {
        setScreen()

        composeRule.onNodeWithText("Alice Tan").assertIsDisplayed()
        composeRule.onNodeWithText("alice@example.com").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Logout").assertIsDisplayed()
        composeRule.onNodeWithTag("streakRingAvatar").assertIsDisplayed()
    }

    @Test
    fun header_blankNameAndMissingEmail_fallBackToDefaults() {
        setScreen(profile = UserProfile(uid = "u2", email = null, displayName = "  "))

        composeRule.onNodeWithText("User").assertIsDisplayed()
        composeRule.onNodeWithText("-").assertIsDisplayed()
    }

    @Test
    fun clickSettings_invokesOnOpenSettings() {
        var opened = 0
        setScreen(onOpenSettings = { opened++ })

        composeRule.onNodeWithContentDescription("Settings").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun clickLogout_invokesOnLogout() {
        var loggedOut = 0
        setScreen(onLogout = { loggedOut++ })

        composeRule.onNodeWithText("Logout").performClick()

        assertEquals(1, loggedOut)
    }

    // ── Data loading ─────────────────────────────────────────────────────────

    @Test
    fun launch_refreshesStreakPointsAndLikesForProfileUid() {
        setScreen()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                coVerify(atLeast = 1) { streakRepo.getWeeklyStreak("u1", any()) }
                coVerify(atLeast = 1) { pointsRepo.getPoints("u1") }
                coVerify(atLeast = 1) { danceRepo.getLikedDances("u1") }
            }.isSuccess
        }
        coVerify(atLeast = 1) { challengeRepo.getOrCreateCurrentWeek("u1") }
    }

    @Test
    fun stats_showPointsStreakDaysAndAchievementLevel() {
        coEvery { pointsRepo.getPoints("u1") } returns PointsSnapshot(totalPoints = 250)
        // Monday and Wednesday done: two active days.
        coEvery { streakRepo.getWeeklyStreak(any(), any()) } returns
            WeeklyStreak(weekStart = "2026-10-26", daysMask = 0b0000101, totalStreakActivities = 12)
        buildViewModels()
        setScreen()

        waitForTextExists("2/7")
        composeRule.onNodeWithTag("profileStats").assertIsDisplayed()
        composeRule.onNodeWithText("250").assertIsDisplayed()
        composeRule.onNodeWithText("2/7").assertIsDisplayed()

        scrollToText("12 Streak Activities")
        composeRule.onNodeWithText("12 Streak Activities").assertIsDisplayed()
        composeRule.onNodeWithText("Weekly Streak").assertIsDisplayed()

        // 250 pts is level 2 (Mover); 500 points reaches Dancer.
        scrollToText("Achievements")
        composeRule.onNodeWithText("🌱 Mover").assertIsDisplayed()
        composeRule.onNodeWithText("250 pts").assertIsDisplayed()
        composeRule.onNodeWithText("250 to Dancer").assertIsDisplayed()
    }

    @Test
    fun stats_labelsAreShown() {
        setScreen()

        composeRule.onNodeWithTag("profileStats").assertIsDisplayed()
        // "Uploads" and "Liked" are stat labels (and also tab titles when the tab row is composed).
        assertTrue(composeRule.onAllNodesWithText("Uploads").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Liked").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText("Streak").assertIsDisplayed()
        composeRule.onNodeWithText("Points").assertIsDisplayed()
    }

    @Test
    fun uploadsStatus_isShownUnderEmail_whenNotLoggedIn() {
        // The embedded uploads tab reports "Not logged in." through onStatus.
        setScreen()

        // The tab content is far down the LazyColumn; it isn't composed (so it
        // can't report its status) until the list has been scrolled there.
        scrollToText("Learners")
        // The status lands in the header, which is off-screen (and so not composed) by now.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching { scrollToText("Not logged in.") }.isSuccess
        }
        composeRule.onNodeWithText("Not logged in.").assertIsDisplayed()
    }

    // ── Weekly challenges summary ────────────────────────────────────────────

    @Test
    fun weeklySummary_noChallenges_showsUnlockHint() {
        setScreen()

        scrollToText("AI Focus Quests")
        composeRule.onNodeWithText("AI Focus Quests").assertIsDisplayed()
        composeRule.onNodeWithText("Complete a practice session to unlock this week's challenges.")
            .assertIsDisplayed()
    }

    @Test
    fun weeklySummary_withChallenges_showsRowsAndCompletedCount() {
        val challenges = listOf(
            WeeklyChallenge(
                id = "focus_drill", type = ChallengeType.FOCUS_DRILL, title = "Sharpen your timing",
                description = "", emoji = "🎯", targetCount = 3, progressCount = 1, rewardPoints = 25
            ),
            WeeklyChallenge(
                id = "style_explorer", type = ChallengeType.STYLE_EXPLORER, title = "Try new styles",
                description = "", emoji = "🧭", targetCount = 2, progressCount = 2, rewardPoints = 30,
                completed = true
            )
        )
        coEvery { challengeRepo.getOrCreateCurrentWeek(any()) } returns
            WeeklyChallengeSet(weekStart = "2026-10-26", challenges = challenges, bonusRewardPoints = 50)
        buildViewModels()
        setScreen()

        scrollToText("AI Focus Quests")
        waitForTextExists("Sharpen your timing")
        composeRule.onNodeWithText("Sharpen your timing").assertIsDisplayed()
        composeRule.onNodeWithText("Try new styles").assertIsDisplayed()
        // One of two completed.
        composeRule.onNodeWithText("1/2").assertIsDisplayed()
        composeRule.onNodeWithText("1/3").assertIsDisplayed()
        composeRule.onAllNodesWithText("✓").assertCountEquals(1)
    }

    @Test
    fun clickViewAll_invokesOnOpenWeeklyChallenges() {
        var opened = 0
        setScreen(onOpenWeeklyChallenges = { opened++ })

        scrollToText("View all")
        composeRule.onNodeWithText("View all").performClick()

        assertEquals(1, opened)
    }

    // ── Tabs ─────────────────────────────────────────────────────────────────

    @Test
    fun tabs_allFiveAreShown() {
        setScreen()

        scrollToText("Learners")
        listOf("Private", "Activity", "Learners").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("UPLOADS", "PRIVATE", "LIKED_VIDEOS", "MY_DANCE", "OTHER_ANALYTICS").forEach {
            composeRule.onNodeWithTag("profileTab_$it").assertIsDisplayed()
        }
    }

    @Test
    fun likedTab_withNoLikes_showsEmptyState() {
        setScreen()

        scrollToText("Learners")
        composeRule.onNodeWithTag("profileTab_LIKED_VIDEOS").performClick()

        scrollToText("No liked videos yet")
        composeRule.onNodeWithText("No liked videos yet").assertIsDisplayed()
        composeRule.onNodeWithText("Heart a dance in the feed and it'll appear here").assertIsDisplayed()
    }
}
