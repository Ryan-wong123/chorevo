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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PointsRepositoryTest {

    private lateinit var db: FirebaseFirestore
    private lateinit var repo: PointsRepository
    private lateinit var usersCollection: CollectionReference
    private lateinit var pointsCollection: CollectionReference
    private lateinit var currentDoc: DocumentReference

    @Before
    fun setup() {
        db = mockk()
        usersCollection = mockk()
        pointsCollection = mockk()
        currentDoc = mockk()
        val userDoc = mockk<DocumentReference>()

        every { db.collection("users") } returns usersCollection
        every { usersCollection.document("uid1") } returns userDoc
        every { userDoc.collection("points") } returns pointsCollection
        every { pointsCollection.document("current") } returns currentDoc

        repo = PointsRepository(db)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun returnSnapshot(snap: DocumentSnapshot) {
        every { currentDoc.get() } returns Tasks.forResult(snap)
    }

    @Test
    fun getPoints_documentExists_returnsStoredTotals() = runTest {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns true
        every { snap.getLong("totalPoints") } returns 1234L
        every { snap.getLong("lastSessionPoints") } returns 87L
        returnSnapshot(snap)

        val result = repo.getPoints("uid1")

        assertEquals(PointsSnapshot(totalPoints = 1234, lastSessionPoints = 87), result)
    }

    @Test
    fun getPoints_documentMissing_returnsZeroSnapshot() = runTest {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns false
        returnSnapshot(snap)

        val result = repo.getPoints("uid1")

        assertEquals(PointsSnapshot(), result)
        assertEquals(0, result.totalPoints)
        assertEquals(0, result.lastSessionPoints)
    }

    @Test
    fun getPoints_missingFields_defaultToZero() = runTest {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns true
        every { snap.getLong("totalPoints") } returns null
        every { snap.getLong("lastSessionPoints") } returns null
        returnSnapshot(snap)

        val result = repo.getPoints("uid1")

        assertEquals(0, result.totalPoints)
        assertEquals(0, result.lastSessionPoints)
    }

    @Test
    fun getPoints_partialFields_onlyMissingOneDefaultsToZero() = runTest {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns true
        every { snap.getLong("totalPoints") } returns 300L
        every { snap.getLong("lastSessionPoints") } returns null
        returnSnapshot(snap)

        val result = repo.getPoints("uid1")

        assertEquals(300, result.totalPoints)
        assertEquals(0, result.lastSessionPoints)
    }

    @Test
    fun getPoints_readsUsersUidPointsCurrentDocument() = runTest {
        val snap = mockk<DocumentSnapshot>()
        every { snap.exists() } returns false
        returnSnapshot(snap)

        repo.getPoints("uid1")

        verify(exactly = 1) { db.collection("users") }
        verify(exactly = 1) { usersCollection.document("uid1") }
        verify(exactly = 1) { pointsCollection.document("current") }
        verify(exactly = 1) { currentDoc.get() }
    }

    @Test
    fun pointsSnapshot_defaultsAreZero() {
        val snapshot = PointsSnapshot()
        assertEquals(0, snapshot.totalPoints)
        assertEquals(0, snapshot.lastSessionPoints)
    }
}
