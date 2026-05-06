package com.example.chorevo.leaderboard

import android.util.Log
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.leaderboard.ViewModel.LeaderboardViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repo: LeaderboardRepository
    private lateinit var vm: LeaderboardViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0

        repo = mockk(relaxed = true)
        every { repo.observeLeaderboard(any(), any(), any()) } returns flowOf(emptyList())
        every { repo.observeFriendIds(any()) } returns flowOf(emptySet())
        vm = LeaderboardViewModel(repo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Initial State ─────────────────────────────────────────────────────────

    @Test
    fun initialState_hasGlobalScope_andSessionsMetric() = runTest {
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(LeaderboardScope.GLOBAL, state.scope)
        assertEquals(LeaderboardMetric.TOTAL_SESSIONS, state.metric)

        collectJob.cancel()
    }

    @Test
    fun initialState_withNoUidBound_isNotLoading_andEntriesEmpty() = runTest {
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.entries.isEmpty())
        assertNull(state.myEntry)

        collectJob.cancel()
    }

    // ── bindUser ──────────────────────────────────────────────────────────────

    @Test
    fun bindUser_triggersLeaderboardObservation() = runTest {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1,
                uid = "uid1",
                displayName = "Alice",
                photoUrl = null,
                totalPracticeSessions = 10,
                avgSimilarityScore = 0.85,
                totalActiveDays = 5,
                isMe = true
            )
        )
        every { repo.observeLeaderboard(any(), any(), "uid1") } returns flowOf(entries)
        every { repo.observeFriendIds("uid1") } returns flowOf(emptySet())

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.entries.size)
        assertEquals("uid1", state.entries[0].uid)
        assertFalse(state.isLoading)

        collectJob.cancel()
    }

    @Test
    fun bindUser_blankUid_doesNotLoadData() = runTest {
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("   ")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.entries.isEmpty())
        assertFalse(state.isLoading)

        collectJob.cancel()
    }

    @Test
    fun bindUser_emptyUid_doesNotLoadData() = runTest {
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.entries.isEmpty())
        assertFalse(state.isLoading)

        collectJob.cancel()
    }

    // ── myEntry extraction ────────────────────────────────────────────────────

    @Test
    fun bindUser_myEntry_isExtracted_fromEntriesWhereIsMe() = runTest {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1,
                uid = "uid2",
                displayName = "Bob",
                photoUrl = null,
                totalPracticeSessions = 15,
                avgSimilarityScore = 0.9,
                totalActiveDays = 8,
                isMe = false
            ),
            LeaderboardEntry(
                rank = 2,
                uid = "uid1",
                displayName = "Alice",
                photoUrl = null,
                totalPracticeSessions = 10,
                avgSimilarityScore = 0.85,
                totalActiveDays = 5,
                isMe = true
            )
        )
        every { repo.observeLeaderboard(any(), any(), "uid1") } returns flowOf(entries)
        every { repo.observeFriendIds("uid1") } returns flowOf(emptySet())

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        testDispatcher.scheduler.advanceUntilIdle()

        val myEntry = vm.uiState.value.myEntry
        assertNotNull(myEntry)
        assertEquals("uid1", myEntry!!.uid)
        assertTrue(myEntry.isMe)

        collectJob.cancel()
    }

    @Test
    fun bindUser_myEntry_isNull_whenNotInLeaderboard() = runTest {
        val entries = listOf(
            LeaderboardEntry(
                rank = 1,
                uid = "uid2",
                displayName = "Bob",
                photoUrl = null,
                totalPracticeSessions = 10,
                avgSimilarityScore = 0.85,
                totalActiveDays = 5,
                isMe = false
            )
        )
        every { repo.observeLeaderboard(any(), any(), "uid1") } returns flowOf(entries)
        every { repo.observeFriendIds("uid1") } returns flowOf(emptySet())

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.myEntry)

        collectJob.cancel()
    }

    // ── setScope ──────────────────────────────────────────────────────────────

    @Test
    fun setScope_friends_updatesStateScope() = runTest {
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        vm.setScope(LeaderboardScope.FRIENDS)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LeaderboardScope.FRIENDS, vm.uiState.value.scope)

        collectJob.cancel()
    }

    @Test
    fun setScope_global_updatesStateScope() = runTest {
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        vm.setScope(LeaderboardScope.FRIENDS)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setScope(LeaderboardScope.GLOBAL)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LeaderboardScope.GLOBAL, vm.uiState.value.scope)

        collectJob.cancel()
    }

    // ── setMetric ─────────────────────────────────────────────────────────────

    @Test
    fun setMetric_avgSimilarity_updatesStateMetric() = runTest {
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        vm.setMetric(LeaderboardMetric.AVG_SIMILARITY)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LeaderboardMetric.AVG_SIMILARITY, vm.uiState.value.metric)

        collectJob.cancel()
    }

    @Test
    fun setMetric_totalActiveDays_updatesStateMetric() = runTest {
        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        vm.setMetric(LeaderboardMetric.TOTAL_ACTIVE_DAYS)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(LeaderboardMetric.TOTAL_ACTIVE_DAYS, vm.uiState.value.metric)

        collectJob.cancel()
    }

    // ── friendIds propagation ─────────────────────────────────────────────────

    @Test
    fun bindUser_friendIds_propagatedToState() = runTest {
        every { repo.observeLeaderboard(any(), any(), "uid1") } returns flowOf(emptyList())
        every { repo.observeFriendIds("uid1") } returns flowOf(setOf("uid2", "uid3"))

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("uid2", "uid3"), vm.uiState.value.friendIds)

        collectJob.cancel()
    }

    // ── addFriend ─────────────────────────────────────────────────────────────

    @Test
    fun addFriend_callsRepoAddFriend_withCorrectArguments() = runTest {
        val entry = LeaderboardEntry(
            rank = 2,
            uid = "uid2",
            displayName = "Bob",
            photoUrl = "https://example.com/bob.jpg",
            totalPracticeSessions = 5,
            avgSimilarityScore = 0.7,
            totalActiveDays = 3,
            isMe = false
        )
        coEvery { repo.addFriend(any(), any(), any(), any()) } returns Unit

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.addFriend(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            repo.addFriend(
                myUid = "uid1",
                friendUid = "uid2",
                displayName = "Bob",
                photoUrl = "https://example.com/bob.jpg"
            )
        }

        collectJob.cancel()
    }

    @Test
    fun addFriend_doesNothing_whenTargetIsCurrentUser() = runTest {
        val selfEntry = LeaderboardEntry(
            rank = 1,
            uid = "uid1",
            displayName = "Alice",
            photoUrl = null,
            totalPracticeSessions = 10,
            avgSimilarityScore = 0.85,
            totalActiveDays = 5,
            isMe = true
        )

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.addFriend(selfEntry)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repo.addFriend(any(), any(), any(), any()) }

        collectJob.cancel()
    }

    @Test
    fun addFriend_doesNothing_whenAlreadyAFriend() = runTest {
        val entry = LeaderboardEntry(
            rank = 2,
            uid = "uid2",
            displayName = "Bob",
            photoUrl = null,
            totalPracticeSessions = 5,
            avgSimilarityScore = 0.7,
            totalActiveDays = 3,
            isMe = false
        )
        every { repo.observeFriendIds("uid1") } returns flowOf(setOf("uid2"))

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.addFriend(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repo.addFriend(any(), any(), any(), any()) }

        collectJob.cancel()
    }

    @Test
    fun addFriend_doesNothing_whenNoUserBound() = runTest {
        val entry = LeaderboardEntry(
            rank = 1,
            uid = "uid2",
            displayName = "Bob",
            photoUrl = null,
            totalPracticeSessions = 5,
            avgSimilarityScore = 0.7,
            totalActiveDays = 3,
            isMe = false
        )

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.addFriend(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repo.addFriend(any(), any(), any(), any()) }

        collectJob.cancel()
    }

    @Test
    fun addFriend_addingFriendUid_isNull_afterCompletion() = runTest {
        val entry = LeaderboardEntry(
            rank = 2,
            uid = "uid2",
            displayName = "Bob",
            photoUrl = null,
            totalPracticeSessions = 5,
            avgSimilarityScore = 0.7,
            totalActiveDays = 3,
            isMe = false
        )
        coEvery { repo.addFriend(any(), any(), any(), any()) } returns Unit

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        vm.addFriend(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.addingFriendUid)

        collectJob.cancel()
    }

    @Test
    fun addFriend_addingFriendUid_isNull_evenOnRepoException() = runTest {
        val entry = LeaderboardEntry(
            rank = 2,
            uid = "uid2",
            displayName = "Bob",
            photoUrl = null,
            totalPracticeSessions = 5,
            avgSimilarityScore = 0.7,
            totalActiveDays = 3,
            isMe = false
        )
        coEvery { repo.addFriend(any(), any(), any(), any()) } throws RuntimeException("network error")

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        vm.addFriend(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.addingFriendUid)

        collectJob.cancel()
    }

    // ── removeFriend ──────────────────────────────────────────────────────────

    @Test
    fun removeFriend_callsRepoRemoveFriend_withCorrectArguments() = runTest {
        val entry = LeaderboardEntry(
            rank = 1,
            uid = "uid2",
            displayName = "Bob",
            photoUrl = null,
            totalPracticeSessions = 5,
            avgSimilarityScore = 0.7,
            totalActiveDays = 3,
            isMe = false
        )
        coEvery { repo.removeFriend(any(), any()) } returns Unit

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.removeFriend(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            repo.removeFriend(myUid = "uid1", friendUid = "uid2")
        }

        collectJob.cancel()
    }

    @Test
    fun removeFriend_doesNothing_whenTargetIsCurrentUser() = runTest {
        val selfEntry = LeaderboardEntry(
            rank = 1,
            uid = "uid1",
            displayName = "Alice",
            photoUrl = null,
            totalPracticeSessions = 10,
            avgSimilarityScore = 0.85,
            totalActiveDays = 5,
            isMe = true
        )

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        testDispatcher.scheduler.advanceUntilIdle()

        vm.removeFriend(selfEntry)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repo.removeFriend(any(), any()) }

        collectJob.cancel()
    }

    @Test
    fun removeFriend_doesNothing_whenNoUserBound() = runTest {
        val entry = LeaderboardEntry(
            rank = 1,
            uid = "uid2",
            displayName = "Bob",
            photoUrl = null,
            totalPracticeSessions = 5,
            avgSimilarityScore = 0.7,
            totalActiveDays = 3,
            isMe = false
        )

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.removeFriend(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repo.removeFriend(any(), any()) }

        collectJob.cancel()
    }

    @Test
    fun removeFriend_removingFriendUid_isNull_afterCompletion() = runTest {
        val entry = LeaderboardEntry(
            rank = 1,
            uid = "uid2",
            displayName = "Bob",
            photoUrl = null,
            totalPracticeSessions = 5,
            avgSimilarityScore = 0.7,
            totalActiveDays = 3,
            isMe = false
        )
        coEvery { repo.removeFriend(any(), any()) } returns Unit

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        vm.removeFriend(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.removingFriendUid)

        collectJob.cancel()
    }

    @Test
    fun removeFriend_removingFriendUid_isNull_evenOnRepoException() = runTest {
        val entry = LeaderboardEntry(
            rank = 1,
            uid = "uid2",
            displayName = "Bob",
            photoUrl = null,
            totalPracticeSessions = 5,
            avgSimilarityScore = 0.7,
            totalActiveDays = 3,
            isMe = false
        )
        coEvery { repo.removeFriend(any(), any()) } throws RuntimeException("network error")

        val collectJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindUser("uid1")
        vm.removeFriend(entry)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.removingFriendUid)

        collectJob.cancel()
    }
}