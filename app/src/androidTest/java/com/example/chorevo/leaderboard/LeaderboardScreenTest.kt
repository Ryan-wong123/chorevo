package com.example.chorevo.leaderboard

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.leaderboard.ViewModel.LeaderboardViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeaderboardScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repo: LeaderboardRepository
    private lateinit var vm: LeaderboardViewModel

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(emptyList())
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)
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

    // ── Top Bar ──────────────────────────────────────────────────────────────

    @Test
    fun topBar_showsLeaderboardTitle() {
        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Leaderboard")
        composeRule.onNodeWithText("Leaderboard").assertIsDisplayed()
    }

    // ── Tabs ─────────────────────────────────────────────────────────────────

    @Test
    fun tabs_showsGlobalAndFriendsTabs() {
        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Global")
        composeRule.onNodeWithText("Global").assertIsDisplayed()
        composeRule.onNodeWithText("Friends").assertIsDisplayed()
    }

    @Test
    fun clickFriendsTab_showsNoFriendsMessage_whenEmpty() {
        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Friends")
        composeRule.onNodeWithText("Friends").performClick()

        waitForTextExists("No friends added yet.")
        composeRule.onNodeWithText("No friends added yet.").assertIsDisplayed()
    }

    @Test
    fun clickGlobalTab_showsNoRankingMessage_whenEmpty() {
        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        // Switch to Friends first, then back to Global
        waitForTextExists("Friends")
        composeRule.onNodeWithText("Friends").performClick()
        composeRule.onNodeWithText("Global").performClick()

        waitForTextExists("No ranking data yet.")
        composeRule.onNodeWithText("No ranking data yet.").assertIsDisplayed()
    }

    // ── Metric Filter Chips ───────────────────────────────────────────────────

    @Test
    fun filterChips_showsAllMetricOptions() {
        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Sessions")
        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeRule.onNodeWithText("Similarity").assertIsDisplayed()
        composeRule.onNodeWithText("Active Days").assertIsDisplayed()
    }

    // ── Empty States ──────────────────────────────────────────────────────────

    @Test
    fun emptyGlobal_showsNoRankingDataMessage() {
        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("No ranking data yet.")
        composeRule.onNodeWithText("No ranking data yet.").assertIsDisplayed()
    }

    @Test
    fun emptyFriends_showsNoFriendsAddedMessage() {
        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Friends")
        composeRule.onNodeWithText("Friends").performClick()

        waitForTextExists("No friends added yet.")
        composeRule.onNodeWithText("No friends added yet.").assertIsDisplayed()
    }

    // ── Entry List ────────────────────────────────────────────────────────────
    @Test
    fun entriesList_showsDisplayNamesAndRanks() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid1", displayName = "Alice", photoUrl = null,
                totalPracticeSessions = 10, avgSimilarityScore = 0.85, totalActiveDays = 5,
                isMe = true
            ),
            LeaderboardEntry(
                rank = 2, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 8, avgSimilarityScore = 0.70, totalActiveDays = 3,
                isMe = false
            )
        )

        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Alice (You)")
        waitForTextExists("Bob")

        composeRule.onNodeWithText("Bob").assertIsDisplayed()

        composeRule.onAllNodesWithText("Alice (You)")
            .assertCountEquals(2)

        composeRule.onAllNodesWithText("#1")
            .assertCountEquals(2)

        composeRule.onAllNodesWithText("#2")
            .assertCountEquals(1)
    }

    @Test
    fun myEntry_showsYourPositionCard() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid1", displayName = "Alice", photoUrl = null,
                totalPracticeSessions = 10, avgSimilarityScore = 0.85, totalActiveDays = 5,
                isMe = true
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Your position")
        composeRule.onNodeWithText("Your position").assertIsDisplayed()
    }

    @Test
    fun myEntry_appendsYouSuffix_toDisplayName() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid1", displayName = "Alice", photoUrl = null,
                totalPracticeSessions = 10, avgSimilarityScore = 0.85, totalActiveDays = 5,
                isMe = true
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Alice (You)")
        assertTrue(
            composeRule.onAllNodesWithText("Alice (You)")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
    }
    // ── Metric Display Values ─────────────────────────────────────────────────

    @Test
    fun sessionsMetric_displaysSessionCount() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 15, avgSimilarityScore = 0.70, totalActiveDays = 3,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Sessions: 15")
        composeRule.onNodeWithText("Sessions: 15").assertIsDisplayed()
    }

    @Test
    fun similarityMetric_displaysFormattedScore() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 8, avgSimilarityScore = 0.75, totalActiveDays = 3,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Bob")
        composeRule.onNodeWithText("Similarity").performClick()

        waitForTextExists("Average similarity: 0.75")
        composeRule.onNodeWithText("Average similarity: 0.75").assertIsDisplayed()
    }

    @Test
    fun activeDaysMetric_displaysActiveDayCount() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 8, avgSimilarityScore = 0.70, totalActiveDays = 12,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Bob")
        composeRule.onNodeWithText("Active Days").performClick()

        waitForTextExists("Active days: 12")
        composeRule.onNodeWithText("Active days: 12").assertIsDisplayed()
    }

    // ── Friend Buttons ────────────────────────────────────────────────────────

    @Test
    fun globalScope_nonFriendEntry_showsAddFriendButton() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 8, avgSimilarityScore = 0.70, totalActiveDays = 3,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Bob")
        composeRule.onNodeWithContentDescription("Add friend").assertIsDisplayed()
    }

    @Test
    fun globalScope_alreadyFriendEntry_doesNotShowAddFriendButton() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 8, avgSimilarityScore = 0.70, totalActiveDays = 3,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(setOf("uid2"))
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Bob")
        assertTrue(
            composeRule.onAllNodesWithContentDescription("Add friend")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun friendsScope_friendEntry_showsRemoveFriendButton() {
        val friendsEntries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 8, avgSimilarityScore = 0.70, totalActiveDays = 3,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(LeaderboardScope.GLOBAL, any(), any()) } returns flowOf(emptyList())
        every { repo.observeLeaderboard(LeaderboardScope.FRIENDS, any(), any()) } returns flowOf(friendsEntries)
        every { repo.observeFriendIds(any()) } returns flowOf(setOf("uid2"))
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Friends")
        composeRule.onNodeWithText("Friends").performClick()

        waitForTextExists("Bob")
        composeRule.onNodeWithContentDescription("Remove friend").assertIsDisplayed()
    }

    @Test
    fun myEntry_inGlobalScope_doesNotShowAddFriendButton() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid1", displayName = "Alice", photoUrl = null,
                totalPracticeSessions = 10, avgSimilarityScore = 0.85, totalActiveDays = 5,
                isMe = true
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Alice (You)")
        assertTrue(
            composeRule.onAllNodesWithContentDescription("Add friend")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    // ── Friend Actions ────────────────────────────────────────────────────────

    @Test
    fun clickAddFriend_callsRepoAddFriend_withCorrectArgs() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 8, avgSimilarityScore = 0.70, totalActiveDays = 3,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())

        var capturedMyUid: String? = null
        var capturedFriendUid: String? = null
        coEvery { repo.addFriend(any(), any(), any(), any()) } answers {
            capturedMyUid = firstArg()
            capturedFriendUid = secondArg()
        }
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Bob")
        composeRule.onNodeWithContentDescription("Add friend").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { capturedMyUid != null }
        assertEquals("uid1", capturedMyUid)
        assertEquals("uid2", capturedFriendUid)
    }

    @Test
    fun clickRemoveFriend_callsRepoRemoveFriend_withCorrectArgs() {
        val friendsEntries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 8, avgSimilarityScore = 0.70, totalActiveDays = 3,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(LeaderboardScope.GLOBAL, any(), any()) } returns flowOf(emptyList())
        every { repo.observeLeaderboard(LeaderboardScope.FRIENDS, any(), any()) } returns flowOf(friendsEntries)
        every { repo.observeFriendIds(any()) } returns flowOf(setOf("uid2"))

        var capturedMyUid: String? = null
        var capturedFriendUid: String? = null
        coEvery { repo.removeFriend(any(), any()) } answers {
            capturedMyUid = firstArg()
            capturedFriendUid = secondArg()
        }
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Friends")
        composeRule.onNodeWithText("Friends").performClick()

        waitForTextExists("Bob")
        composeRule.onNodeWithContentDescription("Remove friend").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { capturedMyUid != null }
        assertEquals("uid1", capturedMyUid)
        assertEquals("uid2", capturedFriendUid)
    }

    // ── Tied Rankings ─────────────────────────────────────────────────────────

    @Test
    fun tiedEntries_showSameRankNumber() {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1, uid = "uid2", displayName = "Bob", photoUrl = null,
                totalPracticeSessions = 10, avgSimilarityScore = 0.85, totalActiveDays = 5,
                isMe = false
            ),
            LeaderboardEntry(
                rank = 1, uid = "uid3", displayName = "Carol", photoUrl = null,
                totalPracticeSessions = 10, avgSimilarityScore = 0.85, totalActiveDays = 5,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(entries)
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)

        composeRule.setContent {
            LeaderboardScreen(myUid = "uid1", vm = vm)
        }

        waitForTextExists("Bob")
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
        composeRule.onNodeWithText("Carol").assertIsDisplayed()
        // Both share rank #1
        assertTrue(
            composeRule.onAllNodesWithText("#1").fetchSemanticsNodes().size >= 2
        )
    }
}
