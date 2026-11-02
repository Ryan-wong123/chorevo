package com.example.chorevo.weeklychallenge.repository

import com.example.chorevo.weeklychallenge.ChallengeType
import com.example.chorevo.weeklychallenge.ScoreMetric
import com.example.chorevo.weeklychallenge.WeeklyChallengeSet
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyChallengeRepositoryTest {

    private lateinit var db: FirebaseFirestore
    private lateinit var functions: FirebaseFunctions
    private lateinit var repo: WeeklyChallengeRepository
    private lateinit var weeklyCollection: CollectionReference

    @Before
    fun setup() {
        db = mockk()
        functions = mockk()
        weeklyCollection = mockk()
        val usersCollection = mockk<CollectionReference>()
        val userDoc = mockk<DocumentReference>()

        every { db.collection("users") } returns usersCollection
        every { usersCollection.document("uid1") } returns userDoc
        every { userDoc.collection("weeklyChallenges") } returns weeklyCollection

        repo = WeeklyChallengeRepository(db, functions)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun currentWeekStart(): String =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

    /** Makes `weeklyChallenges/{week}` read as [snap]. */
    private fun stubWeekDoc(week: String, snap: DocumentSnapshot) {
        val ref = mockk<DocumentReference>()
        every { weeklyCollection.document(week) } returns ref
        every { ref.get() } returns Tasks.forResult(snap)
    }

    private fun missingDoc(): DocumentSnapshot {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns false
        return snap
    }

    private fun weekDoc(
        weekStart: String?,
        challenges: Any? = null,
        allCompleted: Boolean? = null,
        bonusRewardPoints: Long? = null,
        bonusClaimed: Boolean? = null
    ): DocumentSnapshot {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns true
        every { snap.getString("weekStart") } returns weekStart
        every { snap.get("challenges") } returns challenges
        every { snap.getBoolean("allCompleted") } returns allCompleted
        every { snap.getLong("bonusRewardPoints") } returns bonusRewardPoints
        every { snap.getBoolean("bonusClaimed") } returns bonusClaimed
        return snap
    }

    private fun stubCallable(result: Any?): io.mockk.CapturingSlot<Any> {
        val dataSlot = slot<Any>()
        val callable = mockk<HttpsCallableReference>()
        val callableResult = mockk<HttpsCallableResult>()
        every { functions.getHttpsCallable("weeklyChallenges") } returns callable
        every { callable.call(capture(dataSlot)) } returns Tasks.forResult(callableResult)
        every { callableResult.getData() } returns result
        return dataSlot
    }

    private val fullChallenge: Map<String, Any?> = mapOf(
        "type" to "FOCUS_DRILL",
        "title" to "Focus Drill: Timing",
        "description" to "Score 60%+ on Timing in 3 sessions.",
        "emoji" to "T",
        "targetCount" to 3L,
        "progressCount" to 1L,
        "rewardPoints" to 40L,
        "completed" to true,
        "completedAt" to 1_700_000_000_000L,
        "focusMetric" to "TIMING",
        "focusThreshold" to 0.6,
        "requiredStyles" to listOf("salsa", 5, "hiphop"),
        "satisfiedStyles" to listOf("salsa")
    )

    // ── existing week: plain read, no server call ─────────────────────────────

    @Test
    fun getOrCreateCurrentWeek_existingDocument_returnsItWithoutCallingServer() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(
            week,
            weekDoc(
                weekStart = week,
                challenges = mapOf("focus_drill" to fullChallenge),
                allCompleted = true,
                bonusRewardPoints = 75L,
                bonusClaimed = true
            )
        )

        val result = repo.getOrCreateCurrentWeek("uid1")

        assertEquals(week, result.weekStart)
        assertEquals(1, result.challenges.size)
        assertTrue(result.allCompleted)
        assertEquals(75, result.bonusRewardPoints)
        assertTrue(result.bonusClaimed)
        verify(exactly = 0) { functions.getHttpsCallable(any()) }
    }

    @Test
    fun getOrCreateCurrentWeek_parsesAllChallengeFields() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(week, weekDoc(week, challenges = mapOf("focus_drill" to fullChallenge)))

        val challenge = repo.getOrCreateCurrentWeek("uid1").challenges.single()

        assertEquals("focus_drill", challenge.id)
        assertEquals(ChallengeType.FOCUS_DRILL, challenge.type)
        assertEquals("Focus Drill: Timing", challenge.title)
        assertEquals("Score 60%+ on Timing in 3 sessions.", challenge.description)
        assertEquals("T", challenge.emoji)
        assertEquals(3, challenge.targetCount)
        assertEquals(1, challenge.progressCount)
        assertEquals(40, challenge.rewardPoints)
        assertTrue(challenge.completed)
        assertEquals(1_700_000_000_000L, challenge.completedAt)
        assertEquals(ScoreMetric.TIMING, challenge.focusMetric)
        assertEquals(0.6, challenge.focusThreshold!!, 0.0001)
        assertEquals(listOf("salsa", "hiphop"), challenge.requiredStyles)
        assertEquals(listOf("salsa"), challenge.satisfiedStyles)
    }

    @Test
    fun getOrCreateCurrentWeek_challengeWithOnlyType_getsDefaultsForEverythingElse() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(
            week,
            weekDoc(week, challenges = mapOf("momentum_goal" to mapOf<String, Any?>("type" to "MOMENTUM_GOAL")))
        )

        val challenge = repo.getOrCreateCurrentWeek("uid1").challenges.single()

        assertEquals("momentum_goal", challenge.id)
        assertEquals(ChallengeType.MOMENTUM_GOAL, challenge.type)
        assertEquals("", challenge.title)
        assertEquals("", challenge.description)
        assertEquals("", challenge.emoji)
        assertEquals(0, challenge.targetCount)
        assertEquals(0, challenge.progressCount)
        assertEquals(0, challenge.rewardPoints)
        assertFalse(challenge.completed)
        assertNull(challenge.completedAt)
        assertNull(challenge.focusMetric)
        assertNull(challenge.focusThreshold)
        assertTrue(challenge.requiredStyles.isEmpty())
        assertTrue(challenge.satisfiedStyles.isEmpty())
    }

    @Test
    fun getOrCreateCurrentWeek_skipsChallengesWithUnknownOrMissingType() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(
            week,
            weekDoc(
                week,
                challenges = mapOf(
                    "good" to mapOf<String, Any?>("type" to "STYLE_EXPLORER"),
                    "unknown" to mapOf<String, Any?>("type" to "SOMETHING_NEW"),
                    "missing" to mapOf<String, Any?>("title" to "No type")
                )
            )
        )

        val challenges = repo.getOrCreateCurrentWeek("uid1").challenges

        assertEquals(listOf("good"), challenges.map { it.id })
    }

    @Test
    fun getOrCreateCurrentWeek_unknownFocusMetric_becomesNull() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(
            week,
            weekDoc(
                week,
                challenges = mapOf(
                    "focus_drill" to mapOf<String, Any?>("type" to "FOCUS_DRILL", "focusMetric" to "BALANCE")
                )
            )
        )

        val challenge = repo.getOrCreateCurrentWeek("uid1").challenges.single()

        assertEquals(ChallengeType.FOCUS_DRILL, challenge.type)
        assertNull(challenge.focusMetric)
    }

    @Test
    fun getOrCreateCurrentWeek_numbersStoredAsDoubles_areTruncatedToInts() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(
            week,
            weekDoc(
                week,
                challenges = mapOf(
                    "momentum_goal" to mapOf<String, Any?>(
                        "type" to "MOMENTUM_GOAL",
                        "targetCount" to 5.0,
                        "progressCount" to 2.0,
                        "rewardPoints" to 30.0,
                        "completedAt" to 1234.0
                    )
                )
            )
        )

        val challenge = repo.getOrCreateCurrentWeek("uid1").challenges.single()

        assertEquals(5, challenge.targetCount)
        assertEquals(2, challenge.progressCount)
        assertEquals(30, challenge.rewardPoints)
        assertEquals(1234L, challenge.completedAt)
    }

    @Test
    fun getOrCreateCurrentWeek_missingBonusFields_useDefaults() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(week, weekDoc(week, challenges = null))

        val result = repo.getOrCreateCurrentWeek("uid1")

        assertTrue(result.challenges.isEmpty())
        assertFalse(result.allCompleted)
        assertEquals(50, result.bonusRewardPoints)
        assertFalse(result.bonusClaimed)
    }

    @Test
    fun getOrCreateCurrentWeek_documentWithoutWeekStart_isTreatedAsMissingAndAsksServer() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(week, weekDoc(weekStart = null))
        stubCallable(mapOf("weekStart" to week))

        repo.getOrCreateCurrentWeek("uid1")

        verify(exactly = 1) { functions.getHttpsCallable("weeklyChallenges") }
    }

    // ── missing week: ask the server to create it ─────────────────────────────

    @Test
    fun getOrCreateCurrentWeek_missingDocument_callsServerAndReadsTheWeekItReturns() = runTest {
        val localWeek = currentWeekStart()
        stubWeekDoc(localWeek, missingDoc())
        // Server may be in a different week than the phone (time zone edge).
        val serverWeek = "2020-01-06"
        stubWeekDoc(
            serverWeek,
            weekDoc(
                serverWeek,
                challenges = mapOf("style_explorer" to mapOf<String, Any?>("type" to "STYLE_EXPLORER")),
                bonusRewardPoints = 60L
            )
        )
        stubCallable(mapOf("weekStart" to serverWeek))

        val result = repo.getOrCreateCurrentWeek("uid1")

        assertEquals(serverWeek, result.weekStart)
        assertEquals(listOf("style_explorer"), result.challenges.map { it.id })
        assertEquals(60, result.bonusRewardPoints)
    }

    @Test
    fun getOrCreateCurrentWeek_missingDocument_sendsPhoneTimeZoneOffsetInMinutes() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(week, missingDoc())
        val dataSlot = stubCallable(mapOf("weekStart" to week))

        repo.getOrCreateCurrentWeek("uid1")

        val payload = dataSlot.captured as Map<*, *>
        assertEquals(setOf("tzOffsetMinutes"), payload.keys)
        val offset = payload["tzOffsetMinutes"] as Int
        val expected = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        assertEquals(expected, offset)
        assertTrue(offset in -720..840)
    }

    @Test
    fun getOrCreateCurrentWeek_serverReturnsNoWeekStart_fallsBackToLocalWeek() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(week, missingDoc())
        stubCallable(emptyMap<String, Any?>())

        val result = repo.getOrCreateCurrentWeek("uid1")

        assertEquals(WeeklyChallengeSet(weekStart = week), result)
    }

    @Test
    fun getOrCreateCurrentWeek_serverReturnsNonStringWeekStart_fallsBackToLocalWeek() = runTest {
        val week = currentWeekStart()
        stubWeekDoc(week, missingDoc())
        stubCallable(mapOf("weekStart" to 42))

        val result = repo.getOrCreateCurrentWeek("uid1")

        assertEquals(week, result.weekStart)
    }

    @Test
    fun getOrCreateCurrentWeek_serverCreatedWeekStillMissing_returnsEmptySetForThatWeek() = runTest {
        val localWeek = currentWeekStart()
        val serverWeek = "2020-01-06"
        stubWeekDoc(localWeek, missingDoc())
        stubWeekDoc(serverWeek, missingDoc())
        stubCallable(mapOf("weekStart" to serverWeek))

        val result = repo.getOrCreateCurrentWeek("uid1")

        assertEquals(WeeklyChallengeSet(weekStart = serverWeek), result)
        assertTrue(result.challenges.isEmpty())
    }
}
