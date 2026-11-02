package com.example.chorevo.danceactivity.repository

import com.example.chorevo.danceactivity.ALL_BADGES
import com.example.chorevo.danceactivity.BadgeId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementRepositoryTest {

    private lateinit var db: FirebaseFirestore
    private lateinit var repo: AchievementRepository
    private lateinit var usersCollection: CollectionReference
    private lateinit var achievementsCollection: CollectionReference
    private lateinit var currentDoc: DocumentReference

    @Before
    fun setup() {
        db = mockk()
        usersCollection = mockk()
        achievementsCollection = mockk()
        currentDoc = mockk()
        val userDoc = mockk<DocumentReference>()

        every { db.collection("users") } returns usersCollection
        every { usersCollection.document("uid1") } returns userDoc
        every { userDoc.collection("achievements") } returns achievementsCollection
        every { achievementsCollection.document("current") } returns currentDoc

        repo = AchievementRepository(db)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun returnSnapshot(exists: Boolean, earnedBadges: Any? = null) {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns exists
        every { snap.get("earnedBadges") } returns earnedBadges
        every { currentDoc.get() } returns Tasks.forResult(snap)
    }

    // ── getEarnedBadges ───────────────────────────────────────────────────────

    @Test
    fun getEarnedBadges_documentMissing_returnsEmptySet() = runTest {
        returnSnapshot(exists = false)

        assertTrue(repo.getEarnedBadges("uid1").isEmpty())
    }

    @Test
    fun getEarnedBadges_fieldMissing_returnsEmptySet() = runTest {
        returnSnapshot(exists = true, earnedBadges = null)

        assertTrue(repo.getEarnedBadges("uid1").isEmpty())
    }

    @Test
    fun getEarnedBadges_fieldNotAList_returnsEmptySet() = runTest {
        returnSnapshot(exists = true, earnedBadges = "FIRST_STEPS")

        assertTrue(repo.getEarnedBadges("uid1").isEmpty())
    }

    @Test
    fun getEarnedBadges_mapsKnownNamesToBadgeIds() = runTest {
        returnSnapshot(exists = true, earnedBadges = listOf("FIRST_STEPS", "CENTURY", "WEEK_WARRIOR"))

        val result = repo.getEarnedBadges("uid1")

        assertEquals(setOf(BadgeId.FIRST_STEPS, BadgeId.CENTURY, BadgeId.WEEK_WARRIOR), result)
    }

    @Test
    fun getEarnedBadges_ignoresUnknownBadgeNames() = runTest {
        returnSnapshot(exists = true, earnedBadges = listOf("FIRST_STEPS", "NOT_A_BADGE", "first_steps", ""))

        val result = repo.getEarnedBadges("uid1")

        assertEquals(setOf(BadgeId.FIRST_STEPS), result)
    }

    @Test
    fun getEarnedBadges_duplicateNames_collapseIntoSet() = runTest {
        returnSnapshot(exists = true, earnedBadges = listOf("ELITE", "ELITE", "DEDICATED"))

        val result = repo.getEarnedBadges("uid1")

        assertEquals(2, result.size)
        assertEquals(setOf(BadgeId.ELITE, BadgeId.DEDICATED), result)
    }

    @Test
    fun getEarnedBadges_readsUsersUidAchievementsCurrentDocument() = runTest {
        returnSnapshot(exists = false)

        repo.getEarnedBadges("uid1")

        verify(exactly = 1) { usersCollection.document("uid1") }
        verify(exactly = 1) { achievementsCollection.document("current") }
        verify(exactly = 1) { currentDoc.get() }
    }

    // ── getEarnedBadgeDefinitions ─────────────────────────────────────────────

    @Test
    fun getEarnedBadgeDefinitions_returnsDefinitionsForEarnedBadgesOnly() = runTest {
        returnSnapshot(exists = true, earnedBadges = listOf("CENTURY", "FIRST_STEPS"))

        val result = repo.getEarnedBadgeDefinitions("uid1")

        assertEquals(setOf(BadgeId.FIRST_STEPS, BadgeId.CENTURY), result.map { it.id }.toSet())
        assertEquals(2, result.size)
    }

    @Test
    fun getEarnedBadgeDefinitions_followsAllBadgesOrder_notFirestoreOrder() = runTest {
        // Stored in reverse of the ALL_BADGES order.
        returnSnapshot(exists = true, earnedBadges = listOf("WEEK_WARRIOR", "CENTURY", "FIRST_STEPS"))

        val result = repo.getEarnedBadgeDefinitions("uid1")

        assertEquals(
            listOf(BadgeId.FIRST_STEPS, BadgeId.CENTURY, BadgeId.WEEK_WARRIOR),
            result.map { it.id }
        )
    }

    @Test
    fun getEarnedBadgeDefinitions_returnsFullDefinitionObjects() = runTest {
        returnSnapshot(exists = true, earnedBadges = listOf("ELITE"))

        val result = repo.getEarnedBadgeDefinitions("uid1")

        assertEquals(listOf(ALL_BADGES.first { it.id == BadgeId.ELITE }), result)
    }

    @Test
    fun getEarnedBadgeDefinitions_noDocument_returnsEmptyList() = runTest {
        returnSnapshot(exists = false)

        assertTrue(repo.getEarnedBadgeDefinitions("uid1").isEmpty())
    }

    @Test
    fun getEarnedBadgeDefinitions_allBadgesEarned_returnsEveryDefinition() = runTest {
        returnSnapshot(exists = true, earnedBadges = BadgeId.values().map { it.name })

        val result = repo.getEarnedBadgeDefinitions("uid1")

        assertEquals(ALL_BADGES, result)
    }
}
