package com.example.chorevo.leaderboard

import android.util.Log
import com.example.chorevo.leaderboard.repository.LeaderboardRepository
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardRepositoryTest {

    private lateinit var db: FirebaseFirestore
    private lateinit var repo: LeaderboardRepository

    // Shared mock infrastructure for the leaderboardStats query chain
    private lateinit var statsCollection: CollectionReference
    private lateinit var orderedQuery: Query
    private lateinit var limitedQuery: Query
    private lateinit var statsRegistration: ListenerRegistration

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0

        db = mockk(relaxed = true)
        repo = LeaderboardRepository(db)

        statsCollection = mockk()
        orderedQuery = mockk()
        limitedQuery = mockk()
        statsRegistration = mockk(relaxed = true)

        every { db.collection("leaderboardStats") } returns statsCollection
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Helper: build a mock leaderboardStats document ──────────────────────

    private fun mockStatDoc(
        uid: String,
        displayName: String,
        totalSessions: Long = 0,
        avgSimilarity: Double = 0.0,
        totalActiveDays: Long = 0,
        photoUrl: String? = null
    ): DocumentSnapshot {
        val doc = mockk<DocumentSnapshot>()
        every { doc.id } returns uid
        every { doc.getString("uid") } returns uid
        every { doc.getString("displayName") } returns displayName
        every { doc.getString("photoUrl") } returns photoUrl
        every { doc.getLong("totalPracticeSessions") } returns totalSessions
        every { doc.getDouble("avgSimilarityScore") } returns avgSimilarity
        every { doc.getLong("totalActiveDays") } returns totalActiveDays
        every { doc.getLong("streakDays") } returns totalActiveDays
        return doc
    }

    private fun setupStatsChain(
        metric: LeaderboardMetric,
        listenerSlot: io.mockk.CapturingSlot<EventListener<QuerySnapshot>>
    ) {
        every {
            statsCollection.orderBy(metric.firestoreField, Query.Direction.DESCENDING)
        } returns orderedQuery
        every { orderedQuery.limit(100) } returns limitedQuery
        every { limitedQuery.addSnapshotListener(capture(listenerSlot)) } returns statsRegistration
    }

    private fun setupFriendsChain(
        myUid: String,
        listenerSlot: io.mockk.CapturingSlot<EventListener<QuerySnapshot>>,
        registration: ListenerRegistration = mockk(relaxed = true)
    ) {
        val usersCol = mockk<CollectionReference>()
        val userDoc = mockk<DocumentReference>()
        val friendsCol = mockk<CollectionReference>()

        every { db.collection("users") } returns usersCol
        every { usersCol.document(myUid) } returns userDoc
        every { userDoc.collection("friends") } returns friendsCol
        every { friendsCol.addSnapshotListener(capture(listenerSlot)) } returns registration
    }

    // ── observeLeaderboard – GLOBAL scope ────────────────────────────────────

    @Test
    fun observeLeaderboard_global_emitsRankedEntries_orderedByScore() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.TOTAL_SESSIONS, statsListenerSlot)

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(
            mockStatDoc("uid2", "Bob", totalSessions = 5),
            mockStatDoc("uid1", "Alice", totalSessions = 10),
            mockStatDoc("uid3", "Carol", totalSessions = 7)
        )

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.TOTAL_SESSIONS,
                myUid = "uid1"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()

        assertEquals(3, entries.size)
        // Highest sessions first
        assertEquals("uid1", entries[0].uid) // 10 sessions
        assertEquals(1, entries[0].rank)
        assertEquals("uid3", entries[1].uid) // 7 sessions
        assertEquals(2, entries[1].rank)
        assertEquals("uid2", entries[2].uid) // 5 sessions
        assertEquals(3, entries[2].rank)

        verify(exactly = 1) { statsRegistration.remove() }
    }

    @Test
    fun observeLeaderboard_global_setsIsMeFlag_forCurrentUser() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.TOTAL_SESSIONS, statsListenerSlot)

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(
            mockStatDoc("uid1", "Alice", totalSessions = 10),
            mockStatDoc("uid2", "Bob", totalSessions = 5)
        )

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.TOTAL_SESSIONS,
                myUid = "uid1"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()

        assertTrue("Alice entry should have isMe = true", entries[0].isMe)
        assertFalse("Bob entry should have isMe = false", entries[1].isMe)
    }

    @Test
    fun observeLeaderboard_global_tiedEntries_shareSameRank() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.TOTAL_SESSIONS, statsListenerSlot)

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(
            mockStatDoc("uid1", "Alice", totalSessions = 10),
            mockStatDoc("uid2", "Bob", totalSessions = 10),
            mockStatDoc("uid3", "Carol", totalSessions = 5)
        )

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.TOTAL_SESSIONS,
                myUid = "uid9"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()

        assertEquals(3, entries.size)
        // Alice and Bob tied at rank 1
        assertEquals(1, entries.find { it.uid == "uid1" }!!.rank)
        assertEquals(1, entries.find { it.uid == "uid2" }!!.rank)
        // Carol gets rank 3 (positional index + 1)
        assertEquals(3, entries.find { it.uid == "uid3" }!!.rank)
    }

    @Test
    fun observeLeaderboard_global_tiedEntries_sortedAlphabeticallyByDisplayName() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.TOTAL_SESSIONS, statsListenerSlot)

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(
            mockStatDoc("uid2", "Zoe", totalSessions = 10),
            mockStatDoc("uid1", "Alice", totalSessions = 10)
        )

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.TOTAL_SESSIONS,
                myUid = "uid9"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()

        // Alice comes before Zoe alphabetically
        assertEquals("uid1", entries[0].uid)
        assertEquals("uid2", entries[1].uid)
    }

    @Test
    fun observeLeaderboard_global_emptySnapshot_returnsEmptyList() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.TOTAL_SESSIONS, statsListenerSlot)

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns emptyList()

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.TOTAL_SESSIONS,
                myUid = "uid1"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun observeLeaderboard_global_firestoreError_emitsEmptyList() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.TOTAL_SESSIONS, statsListenerSlot)

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.TOTAL_SESSIONS,
                myUid = "uid1"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(null, mockk(relaxed = true))
        advanceUntilIdle()

        val entries = deferred.await()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun observeLeaderboard_global_avgSimilarityMetric_usesCorrectField() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.AVG_SIMILARITY, statsListenerSlot)

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(
            mockStatDoc("uid1", "Alice", avgSimilarity = 0.9),
            mockStatDoc("uid2", "Bob", avgSimilarity = 0.6)
        )

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.AVG_SIMILARITY,
                myUid = "uid9"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()
        assertEquals(2, entries.size)
        assertEquals(0.9, entries[0].avgSimilarityScore, 0.001)
        assertEquals(0.6, entries[1].avgSimilarityScore, 0.001)
    }

    @Test
    fun observeLeaderboard_global_activeDaysMetric_ordersCorrectly() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.TOTAL_ACTIVE_DAYS, statsListenerSlot)

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(
            mockStatDoc("uid2", "Bob", totalActiveDays = 3),
            mockStatDoc("uid1", "Alice", totalActiveDays = 20)
        )

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.TOTAL_ACTIVE_DAYS,
                myUid = "uid9"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()
        assertEquals("uid1", entries[0].uid)  // 20 active days
        assertEquals("uid2", entries[1].uid)  // 3 active days
    }

    // ── observeLeaderboard – FRIENDS scope ───────────────────────────────────

    private fun setupFriendStatsChain(
        listenerSlot: io.mockk.CapturingSlot<EventListener<QuerySnapshot>>,
        requestedIds: io.mockk.CapturingSlot<List<Any>>
    ) {
        val byIdQuery = mockk<Query>()
        every { statsCollection.whereIn(any<FieldPath>(), capture(requestedIds)) } returns byIdQuery
        every { byIdQuery.addSnapshotListener(capture(listenerSlot)) } returns statsRegistration
    }

    @Test
    fun observeLeaderboard_friends_readsFriendsAndSelfById_evenOutsideTop100() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        val friendsListenerSlot = slot<EventListener<QuerySnapshot>>()
        val requestedIds = slot<List<Any>>()

        setupFriendsChain("uid1", friendsListenerSlot)
        setupFriendStatsChain(statsListenerSlot, requestedIds)

        val friendsSnapshot = mockk<QuerySnapshot>()
        val friendDoc = mockk<DocumentSnapshot>()
        every { friendDoc.id } returns "uid2"
        every { friendsSnapshot.documents } returns listOf(friendDoc)

        // Only the requested users come back; Bob may be far outside the global top 100.
        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(
            mockStatDoc("uid1", "Alice", totalSessions = 10),
            mockStatDoc("uid2", "Bob", totalSessions = 12)
        )

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.FRIENDS,
                metric = LeaderboardMetric.TOTAL_SESSIONS,
                myUid = "uid1"
            ).first()
        }

        advanceUntilIdle()
        friendsListenerSlot.captured.onEvent(friendsSnapshot, null)
        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()

        assertEquals(setOf("uid1", "uid2"), requestedIds.captured.toSet())
        assertEquals(listOf("uid2", "uid1"), entries.map { it.uid })
        assertEquals(1, entries[0].rank)
        assertTrue(entries[1].isMe)
    }

    @Test
    fun observeLeaderboard_friends_selfAlwaysIncluded() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        val friendsListenerSlot = slot<EventListener<QuerySnapshot>>()
        val requestedIds = slot<List<Any>>()

        setupFriendsChain("uid1", friendsListenerSlot)
        setupFriendStatsChain(statsListenerSlot, requestedIds)

        val friendsSnapshot = mockk<QuerySnapshot>()
        every { friendsSnapshot.documents } returns emptyList()

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(mockStatDoc("uid1", "Alice", totalSessions = 10))

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.FRIENDS,
                metric = LeaderboardMetric.TOTAL_SESSIONS,
                myUid = "uid1"
            ).first()
        }

        advanceUntilIdle()
        friendsListenerSlot.captured.onEvent(friendsSnapshot, null)
        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()

        assertEquals(listOf("uid1"), requestedIds.captured)
        assertEquals(1, entries.size)
        assertEquals("uid1", entries[0].uid)
    }

    // ── observeFriendIds ─────────────────────────────────────────────────────

    @Test
    fun observeFriendIds_emitsSetOfFriendUids() = runTest {
        val friendsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupFriendsChain("uid1", friendsListenerSlot)

        val friendsSnapshot = mockk<QuerySnapshot>()
        val doc1 = mockk<DocumentSnapshot>().also { every { it.id } returns "uid2" }
        val doc2 = mockk<DocumentSnapshot>().also { every { it.id } returns "uid3" }
        every { friendsSnapshot.documents } returns listOf(doc1, doc2)

        val deferred = async {
            repo.observeFriendIds("uid1").first()
        }

        advanceUntilIdle()
        friendsListenerSlot.captured.onEvent(friendsSnapshot, null)
        advanceUntilIdle()

        val ids = deferred.await()

        assertEquals(setOf("uid2", "uid3"), ids)
    }

    @Test
    fun observeFriendIds_emitsEmptySet_whenNoFriends() = runTest {
        val friendsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupFriendsChain("uid1", friendsListenerSlot)

        val friendsSnapshot = mockk<QuerySnapshot>()
        every { friendsSnapshot.documents } returns emptyList()

        val deferred = async {
            repo.observeFriendIds("uid1").first()
        }

        advanceUntilIdle()
        friendsListenerSlot.captured.onEvent(friendsSnapshot, null)
        advanceUntilIdle()

        val ids = deferred.await()
        assertTrue(ids.isEmpty())
    }

    @Test
    fun observeFriendIds_firestoreError_emitsEmptySet() = runTest {
        val friendsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupFriendsChain("uid1", friendsListenerSlot)

        val deferred = async {
            repo.observeFriendIds("uid1").first()
        }

        advanceUntilIdle()
        friendsListenerSlot.captured.onEvent(null, mockk(relaxed = true))
        advanceUntilIdle()

        val ids = deferred.await()
        assertTrue(ids.isEmpty())
    }

    // ── Document fallback: streakDays legacy field ────────────────────────────

    @Test
    fun observeLeaderboard_usesStreakDaysAsFallback_whenTotalActiveDaysMissing() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.TOTAL_ACTIVE_DAYS, statsListenerSlot)

        val doc = mockk<DocumentSnapshot>()
        every { doc.id } returns "uid1"
        every { doc.getString("uid") } returns "uid1"
        every { doc.getString("displayName") } returns "Alice"
        every { doc.getString("photoUrl") } returns null
        every { doc.getLong("totalPracticeSessions") } returns 5L
        every { doc.getDouble("avgSimilarityScore") } returns 0.0
        every { doc.getLong("totalActiveDays") } returns null    // missing
        every { doc.getLong("streakDays") } returns 7L           // legacy fallback

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(doc)

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.TOTAL_ACTIVE_DAYS,
                myUid = "uid1"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()
        assertEquals(1, entries.size)
        assertEquals(7, entries[0].totalActiveDays)
    }

    // ── Document with blank displayName defaults to "User" ───────────────────

    @Test
    fun observeLeaderboard_blankDisplayName_defaultsToUser() = runTest {
        val statsListenerSlot = slot<EventListener<QuerySnapshot>>()
        setupStatsChain(LeaderboardMetric.TOTAL_SESSIONS, statsListenerSlot)

        val doc = mockk<DocumentSnapshot>()
        every { doc.id } returns "uid1"
        every { doc.getString("uid") } returns "uid1"
        every { doc.getString("displayName") } returns "   "   // blank
        every { doc.getString("photoUrl") } returns null
        every { doc.getLong("totalPracticeSessions") } returns 3L
        every { doc.getDouble("avgSimilarityScore") } returns 0.0
        every { doc.getLong("totalActiveDays") } returns 0L
        every { doc.getLong("streakDays") } returns 0L

        val statsSnapshot = mockk<QuerySnapshot>()
        every { statsSnapshot.documents } returns listOf(doc)

        val deferred = async {
            repo.observeLeaderboard(
                scope = LeaderboardScope.GLOBAL,
                metric = LeaderboardMetric.TOTAL_SESSIONS,
                myUid = "uid1"
            ).first()
        }

        advanceUntilIdle()
        statsListenerSlot.captured.onEvent(statsSnapshot, null)
        advanceUntilIdle()

        val entries = deferred.await()
        assertEquals("User", entries[0].displayName)
    }
}
