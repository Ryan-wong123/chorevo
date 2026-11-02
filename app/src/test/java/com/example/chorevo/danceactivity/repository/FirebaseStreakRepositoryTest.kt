package com.example.chorevo.danceactivity.repository

import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseStreakRepositoryTest {

    private lateinit var db: FirebaseFirestore
    private lateinit var repo: FirebaseStreakRepository
    private lateinit var usersCollection: CollectionReference
    private lateinit var streaksCollection: CollectionReference
    private lateinit var currentDoc: DocumentReference

    @Before
    fun setup() {
        db = mockk()
        usersCollection = mockk()
        streaksCollection = mockk()
        currentDoc = mockk()
        val userDoc = mockk<DocumentReference>()

        every { db.collection("users") } returns usersCollection
        every { usersCollection.document("uid1") } returns userDoc
        every { userDoc.collection("streaks") } returns streaksCollection
        every { streaksCollection.document("current") } returns currentDoc

        repo = FirebaseStreakRepository(db)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun thisWeekStart(): String =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

    private fun returnSnapshot(
        exists: Boolean = true,
        weekStart: String? = null,
        daysMask: Long? = null,
        total: Long? = null,
        lastActivity: String? = null
    ) {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns exists
        every { snap.getString("weekStart") } returns weekStart
        every { snap.getLong("daysMask") } returns daysMask
        every { snap.getLong("totalStreakActivities") } returns total
        every { snap.getString("lastActivityDate") } returns lastActivity
        every { currentDoc.get() } returns Tasks.forResult(snap)
    }

    @Test
    fun getWeeklyStreak_documentMissing_returnsEmptyStreakForThisWeek() = runTest {
        returnSnapshot(exists = false)

        val result = repo.getWeeklyStreak("uid1")

        assertEquals(WeeklyStreak(weekStart = thisWeekStart()), result)
        assertEquals(0, result.daysMask)
        assertEquals(0, result.totalStreakActivities)
        assertNull(result.lastActivityDate)
    }

    @Test
    fun getWeeklyStreak_storedWeekIsCurrent_returnsStoredValuesUnchanged() = runTest {
        val week = thisWeekStart()
        returnSnapshot(
            weekStart = week,
            daysMask = 0b0000101L,
            total = 12L,
            lastActivity = "2026-06-17"
        )

        val result = repo.getWeeklyStreak("uid1")

        assertEquals(week, result.weekStart)
        assertEquals(5, result.daysMask)
        assertEquals(12, result.totalStreakActivities)
        assertEquals("2026-06-17", result.lastActivityDate)
    }

    @Test
    fun getWeeklyStreak_storedWeekHasEnded_showsEmptyWeekButKeepsTotals() = runTest {
        returnSnapshot(
            weekStart = "2020-01-06",
            daysMask = 0b1111111L,
            total = 40L,
            lastActivity = "2020-01-12"
        )

        val result = repo.getWeeklyStreak("uid1")

        assertEquals(thisWeekStart(), result.weekStart)
        assertEquals(0, result.daysMask)
        assertEquals(40, result.totalStreakActivities)
        assertEquals("2020-01-12", result.lastActivityDate)
    }

    @Test
    fun getWeeklyStreak_storedWeekInTheFuture_isAlsoTreatedAsDifferentWeek() = runTest {
        returnSnapshot(weekStart = "2999-01-04", daysMask = 3L, total = 2L)

        val result = repo.getWeeklyStreak("uid1")

        assertEquals(thisWeekStart(), result.weekStart)
        assertEquals(0, result.daysMask)
        assertEquals(2, result.totalStreakActivities)
    }

    @Test
    fun getWeeklyStreak_missingFields_fallBackToDefaults() = runTest {
        returnSnapshot(weekStart = null, daysMask = null, total = null, lastActivity = null)

        val result = repo.getWeeklyStreak("uid1")

        assertEquals(thisWeekStart(), result.weekStart)
        assertEquals(0, result.daysMask)
        assertEquals(0, result.totalStreakActivities)
        assertNull(result.lastActivityDate)
    }

    @Test
    fun getWeeklyStreak_autoResetFlagFalse_stillResetsEndedWeek() = runTest {
        returnSnapshot(weekStart = "2020-01-06", daysMask = 9L, total = 7L)

        val result = repo.getWeeklyStreak("uid1", autoResetIfNewWeek = false)

        assertEquals(thisWeekStart(), result.weekStart)
        assertEquals(0, result.daysMask)
        assertEquals(7, result.totalStreakActivities)
    }

    @Test
    fun getWeeklyStreak_readsUsersUidStreaksCurrentDocument() = runTest {
        returnSnapshot(exists = false)

        repo.getWeeklyStreak("uid1")

        verify(exactly = 1) { usersCollection.document("uid1") }
        verify(exactly = 1) { streaksCollection.document("current") }
        verify(exactly = 1) { currentDoc.get() }
    }

    @Test
    fun weeklyStreak_defaultsAreEmpty() {
        val streak = WeeklyStreak()
        assertEquals("", streak.weekStart)
        assertEquals(0, streak.daysMask)
        assertEquals(0, streak.totalStreakActivities)
        assertNull(streak.lastActivityDate)
    }
}
