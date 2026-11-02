package com.example.chorevo.leaderboard

import com.example.chorevo.leaderboard.repository.LeaderboardRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.AggregateQuery
import com.google.firebase.firestore.AggregateQuerySnapshot
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for [LeaderboardRepository.observeMyGlobalEntry]. */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardMyEntryTest {

    private lateinit var db: FirebaseFirestore
    private lateinit var repo: LeaderboardRepository
    private lateinit var statsCollection: CollectionReference
    private lateinit var myDocRef: DocumentReference
    private lateinit var registration: ListenerRegistration
    private val listenerSlot = slot<EventListener<DocumentSnapshot>>()

    @Before
    fun setup() {
        db = mockk(relaxed = true)
        statsCollection = mockk()
        myDocRef = mockk()
        registration = mockk(relaxed = true)

        every { db.collection("leaderboardStats") } returns statsCollection
        every { statsCollection.document("uid1") } returns myDocRef
        every { myDocRef.addSnapshotListener(capture(listenerSlot)) } returns registration

        repo = LeaderboardRepository(db)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun myDoc(
        exists: Boolean = true,
        displayName: String? = "Alice",
        photoUrl: String? = "https://example.com/a.png",
        sessions: Long = 10L,
        avg: Double = 0.75,
        activeDays: Long? = 4L,
        streakDays: Long? = 4L,
        sessionsScore: Double? = null,
        streakScore: Double? = null
    ): DocumentSnapshot {
        val doc = mockk<DocumentSnapshot>()
        every { doc.exists() } returns exists
        every { doc.id } returns "uid1"
        every { doc.getString("uid") } returns "uid1"
        every { doc.getString("displayName") } returns displayName
        every { doc.getString("photoUrl") } returns photoUrl
        every { doc.getLong("totalPracticeSessions") } returns sessions
        every { doc.getDouble("avgSimilarityScore") } returns avg
        every { doc.getLong("totalActiveDays") } returns activeDays
        every { doc.getLong("streakDays") } returns streakDays
        every { doc.getDouble("totalPracticeSessions") } returns sessionsScore
        every { doc.getDouble("streakDays") } returns streakScore
        return doc
    }

    /** Makes `leaderboardStats.whereGreaterThan(field, score).count()` report [higher]. */
    private fun stubHigherCount(field: String, score: Double, higher: Long) {
        val query = mockk<Query>()
        val aggregate = mockk<AggregateQuery>()
        val aggregateSnap = mockk<AggregateQuerySnapshot>()
        every { statsCollection.whereGreaterThan(field, score) } returns query
        every { query.count() } returns aggregate
        every { aggregate.get(AggregateSource.SERVER) } returns Tasks.forResult(aggregateSnap)
        every { aggregateSnap.count } returns higher
    }

    private fun stubHigherCountFailure(field: String, score: Double) {
        val query = mockk<Query>()
        val aggregate = mockk<AggregateQuery>()
        every { statsCollection.whereGreaterThan(field, score) } returns query
        every { query.count() } returns aggregate
        every { aggregate.get(AggregateSource.SERVER) } returns
            Tasks.forException(RuntimeException("offline"))
    }

    // ── rank ──────────────────────────────────────────────────────────────────

    @Test
    fun observeMyGlobalEntry_nobodyHigher_isRankOne_andFlaggedAsMe() = runTest {
        stubHigherCount("totalPracticeSessions", 10.0, higher = 0L)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(sessions = 10L), null)
        advanceUntilIdle()

        assertEquals(1, emitted.size)
        val entry = emitted.single()!!
        assertEquals(1, entry.rank)
        assertEquals("uid1", entry.uid)
        assertTrue(entry.isMe)
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_rankIsOnePlusUsersWithStrictlyHigherScore() = runTest {
        stubHigherCount("totalPracticeSessions", 10.0, higher = 137L)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(sessions = 10L), null)
        advanceUntilIdle()

        assertEquals(138, emitted.single()!!.rank)
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_copiesStatFieldsIntoEntry() = runTest {
        stubHigherCount("totalPracticeSessions", 10.0, higher = 2L)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(
            myDoc(displayName = "Alice", photoUrl = "https://example.com/a.png", sessions = 10L, avg = 0.75, activeDays = 4L),
            null
        )
        advanceUntilIdle()

        val entry = emitted.single()!!
        assertEquals("Alice", entry.displayName)
        assertEquals("https://example.com/a.png", entry.photoUrl)
        assertEquals(10, entry.totalPracticeSessions)
        assertEquals(0.75, entry.avgSimilarityScore, 0.0001)
        assertEquals(4, entry.totalActiveDays)
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_blankDisplayName_defaultsToUser() = runTest {
        stubHigherCount("totalPracticeSessions", 10.0, higher = 0L)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(displayName = "   "), null)
        advanceUntilIdle()

        assertEquals("User", emitted.single()!!.displayName)
        job.cancel()
    }

    // ── which score is compared ───────────────────────────────────────────────

    @Test
    fun observeMyGlobalEntry_usesMetricFieldValueFromDocument_whenPresent() = runTest {
        // The stored field value (12.0) wins over the value derived from the stat (10).
        stubHigherCount("totalPracticeSessions", 12.0, higher = 3L)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(sessions = 10L, sessionsScore = 12.0), null)
        advanceUntilIdle()

        assertEquals(4, emitted.single()!!.rank)
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_avgSimilarityMetric_comparesAverageScore() = runTest {
        stubHigherCount("avgSimilarityScore", 0.75, higher = 1L)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.AVG_SIMILARITY, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(avg = 0.75), null)
        advanceUntilIdle()

        assertEquals(2, emitted.single()!!.rank)
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_activeDaysMetric_queriesStreakDaysField() = runTest {
        stubHigherCount("streakDays", 6.0, higher = 4L)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_ACTIVE_DAYS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(activeDays = 6L, streakDays = 6L, streakScore = null), null)
        advanceUntilIdle()

        val entry = emitted.single()!!
        assertEquals(5, entry.rank)
        assertEquals(6, entry.totalActiveDays)
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_activeDaysFallsBackToLegacyStreakDays() = runTest {
        stubHigherCount("streakDays", 9.0, higher = 0L)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_ACTIVE_DAYS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(activeDays = null, streakDays = 9L, streakScore = 9.0), null)
        advanceUntilIdle()

        assertEquals(9, emitted.single()!!.totalActiveDays)
        job.cancel()
    }

    // ── no stats / errors ─────────────────────────────────────────────────────

    @Test
    fun observeMyGlobalEntry_documentMissing_emitsNull() = runTest {
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(exists = false), null)
        advanceUntilIdle()

        assertEquals(1, emitted.size)
        assertNull(emitted.single())
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_nullDocument_emitsNull() = runTest {
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(null, null)
        advanceUntilIdle()

        assertEquals(1, emitted.size)
        assertNull(emitted.single())
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_listenerError_emitsNull() = runTest {
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(), mockk<FirebaseFirestoreException>(relaxed = true))
        advanceUntilIdle()

        assertEquals(1, emitted.size)
        assertNull(emitted.single())
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_countQueryFails_emitsNothing() = runTest {
        stubHigherCountFailure("totalPracticeSessions", 10.0)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(sessions = 10L), null)
        advanceUntilIdle()

        assertTrue(emitted.isEmpty())
        job.cancel()
    }

    // ── live updates and cleanup ──────────────────────────────────────────────

    @Test
    fun observeMyGlobalEntry_reEmitsWhenDocumentChanges() = runTest {
        stubHigherCount("totalPracticeSessions", 10.0, higher = 5L)
        stubHigherCount("totalPracticeSessions", 20.0, higher = 1L)
        val emitted = mutableListOf<LeaderboardEntry?>()
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { emitted += it }
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(sessions = 10L), null)
        advanceUntilIdle()
        listenerSlot.captured.onEvent(myDoc(sessions = 20L), null)
        advanceUntilIdle()

        assertEquals(listOf(6, 2), emitted.map { it!!.rank })
        job.cancel()
    }

    @Test
    fun observeMyGlobalEntry_registersOnMyStatsDocument_andRemovesListenerOnCancel() = runTest {
        val job = launch {
            repo.observeMyGlobalEntry(LeaderboardMetric.TOTAL_SESSIONS, "uid1").collect { }
        }

        advanceUntilIdle()
        assertNotNull(listenerSlot.captured)
        verify(exactly = 1) { statsCollection.document("uid1") }
        verify(exactly = 0) { registration.remove() }

        job.cancel()
        advanceUntilIdle()

        verify(exactly = 1) { registration.remove() }
    }
}
