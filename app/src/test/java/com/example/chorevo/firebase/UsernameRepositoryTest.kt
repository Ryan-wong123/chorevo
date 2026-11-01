package com.example.chorevo.firebase

import com.example.chorevo.firebase.repository.UserSearchResult
import com.example.chorevo.firebase.repository.UsernameRepository
import com.example.chorevo.testing.MainDispatcherRule
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.WriteBatch
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** JVM tests for [UsernameRepository] using MockK Firestore chains and completed Tasks. */
@OptIn(ExperimentalCoroutinesApi::class)
class UsernameRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var firestore: FirebaseFirestore
    private lateinit var usernamesCol: CollectionReference
    private lateinit var usersCol: CollectionReference
    private lateinit var repo: UsernameRepository

    @Before
    fun setUp() {
        firestore = mockk()
        usernamesCol = mockk()
        usersCol = mockk()
        every { firestore.collection("usernames") } returns usernamesCol
        every { firestore.collection("users") } returns usersCol
        repo = UsernameRepository(firestore)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun ok(): Task<Void> = Tasks.forResult(null)

    private fun <T> failed(e: Exception): Task<T> = Tasks.forException(e)

    private fun snap(
        exists: Boolean = true,
        id: String = "x",
        strings: Map<String, String?> = emptyMap()
    ): DocumentSnapshot {
        val s = mockk<DocumentSnapshot>()
        every { s.exists() } returns exists
        every { s.id } returns id
        every { s.getString(any<String>()) } answers { strings[firstArg<String>()] }
        return s
    }

    /** `usernames/{handle}` whose read reports [taken]. */
    private fun handleDoc(handle: String, taken: Boolean): DocumentReference {
        val doc = mockk<DocumentReference>()
        every { usernamesCol.document(handle) } returns doc
        every { doc.get() } returns Tasks.forResult(snap(exists = taken))
        return doc
    }

    private fun userDoc(uid: String): DocumentReference {
        val doc = mockk<DocumentReference>()
        every { usersCol.document(uid) } returns doc
        return doc
    }

    private class SetCall(val ref: DocumentReference, val data: Any, val options: SetOptions?)

    private fun stubBatch(commit: Task<Void> = Tasks.forResult(null)): Pair<WriteBatch, MutableList<SetCall>> {
        val batch = mockk<WriteBatch>()
        val sets = mutableListOf<SetCall>()
        every { firestore.batch() } returns batch
        every { batch.set(any<DocumentReference>(), any()) } answers {
            sets += SetCall(firstArg<DocumentReference>(), secondArg<Any>(), null)
            batch
        }
        every { batch.set(any<DocumentReference>(), any(), any<SetOptions>()) } answers {
            sets += SetCall(firstArg<DocumentReference>(), secondArg<Any>(), thirdArg<SetOptions>())
            batch
        }
        every { batch.delete(any()) } returns batch
        every { batch.commit() } returns commit
        return batch to sets
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any.asMap(): Map<String, Any?> = this as Map<String, Any?>

    // ── isAvailable ─────────────────────────────────────────────────────

    @Test
    fun isAvailable_freeHandle_isTrue() = runTest {
        handleDoc("ryan", taken = false)

        assertEquals(true, repo.isAvailable("ryan").getOrNull())
    }

    @Test
    fun isAvailable_takenHandle_isFalse() = runTest {
        handleDoc("ryan", taken = true)

        assertEquals(false, repo.isAvailable("ryan").getOrNull())
    }

    @Test
    fun isAvailable_readFailure_isFailure() = runTest {
        val doc = mockk<DocumentReference>()
        every { usernamesCol.document("ryan") } returns doc
        every { doc.get() } returns failed(RuntimeException("offline"))

        val result = repo.isAvailable("ryan")

        assertTrue(result.isFailure)
        assertEquals("offline", result.exceptionOrNull()?.message)
    }

    // ── suggest ─────────────────────────────────────────────────────────

    @Test
    fun suggest_freeBase_returnsPlainSuggestion() = runTest {
        handleDoc("ryan_wong", taken = false)

        assertEquals("ryan_wong", repo.suggest("Ryan Wong"))
    }

    @Test
    fun suggest_takenBase_addsNumberToFreeCandidate() = runTest {
        // Every handle is free except the plain suggestion.
        val takenDoc = mockk<DocumentReference>()
        every { takenDoc.get() } returns Tasks.forResult(snap(exists = true))
        val freeDoc = mockk<DocumentReference>()
        every { freeDoc.get() } returns Tasks.forResult(snap(exists = false))
        every { usernamesCol.document(any<String>()) } answers {
            if (firstArg<String>() == "ryan_wong") takenDoc else freeDoc
        }

        val suggestion = repo.suggest("Ryan Wong")

        assertNotEquals("ryan_wong", suggestion)
        assertTrue(suggestion.startsWith("ryan_wong"))
        assertTrue(Usernames.isValid(suggestion))
        assertTrue(suggestion.removePrefix("ryan_wong").all { it.isDigit() })
    }

    @Test
    fun suggest_everythingTaken_fallsBackToBase() = runTest {
        val takenDoc = mockk<DocumentReference>()
        every { takenDoc.get() } returns Tasks.forResult(snap(exists = true))
        every { usernamesCol.document(any<String>()) } returns takenDoc

        assertEquals("ryan_wong", repo.suggest("Ryan Wong"))
    }

    @Test
    fun suggest_blankDisplayName_usesDancerDefault() = runTest {
        handleDoc("dancer", taken = false)

        assertEquals("dancer", repo.suggest(null))
    }

    // ── claim ───────────────────────────────────────────────────────────

    @Test
    fun claim_invalidHandle_failsWithProblemAndWritesNothing() = runTest {
        val result = repo.claim("u1", "ab", null)

        assertTrue(result.isFailure)
        assertEquals(Usernames.problem("ab"), result.exceptionOrNull()?.message)
        verify(exactly = 0) { firestore.batch() }
    }

    @Test
    fun claim_invalidCharacters_fails() = runTest {
        val result = repo.claim("u1", "Bad Name!", null)

        assertTrue(result.isFailure)
        assertEquals("Use a–z, 0–9 and _ only", result.exceptionOrNull()?.message)
    }

    @Test
    fun claim_sameAsCurrent_succeedsWithoutBatch() = runTest {
        val result = repo.claim("u1", "ryan", current = "ryan")

        assertEquals("ryan", result.getOrNull())
        verify(exactly = 0) { firestore.batch() }
    }

    @Test
    fun claim_newHandle_writesHandleAndProfileInOneBatch() = runTest {
        val handle = mockk<DocumentReference>()
        every { usernamesCol.document("ryan") } returns handle
        val user = userDoc("u1")
        val (batch, sets) = stubBatch()

        val result = repo.claim("u1", "ryan", current = null)

        assertEquals("ryan", result.getOrNull())
        assertEquals(2, sets.size)
        assertEquals(handle, sets[0].ref)
        assertEquals("u1", sets[0].data.asMap()["uid"])
        assertTrue((sets[0].data.asMap()["createdAt"] as Long) > 0L)
        assertEquals(user, sets[1].ref)
        assertEquals(mapOf("username" to "ryan"), sets[1].data)
        assertEquals(SetOptions.merge(), sets[1].options)
        verify(exactly = 0) { batch.delete(any()) }
        verify(exactly = 1) { batch.commit() }
    }

    @Test
    fun claim_blankCurrent_isTreatedAsNoCurrentHandle() = runTest {
        every { usernamesCol.document("ryan") } returns mockk<DocumentReference>()
        userDoc("u1")
        val (batch, _) = stubBatch()

        val result = repo.claim("u1", "ryan", current = "  ")

        assertTrue(result.isSuccess)
        verify(exactly = 0) { batch.delete(any()) }
    }

    @Test
    fun claim_changingHandle_releasesOldHandleInSameBatch() = runTest {
        every { usernamesCol.document("newname") } returns mockk<DocumentReference>()
        val old = mockk<DocumentReference>()
        every { usernamesCol.document("oldname") } returns old
        userDoc("u1")
        val (batch, _) = stubBatch()

        val result = repo.claim("u1", "newname", current = "oldname")

        assertEquals("newname", result.getOrNull())
        verify(exactly = 1) { batch.delete(old) }
        verify(exactly = 1) { batch.commit() }
    }

    @Test
    fun claim_commitRejectedAndHandleTaken_reportsTaken() = runTest {
        val handle = handleDoc("ryan", taken = true)
        userDoc("u1")
        stubBatch(commit = failed(RuntimeException("PERMISSION_DENIED")))

        val result = repo.claim("u1", "ryan", current = null)

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("@ryan is taken", result.exceptionOrNull()?.message)
        verify(exactly = 1) { handle.get() }
    }

    @Test
    fun claim_commitFailsButHandleFree_rethrowsOriginalError() = runTest {
        handleDoc("ryan", taken = false)
        userDoc("u1")
        stubBatch(commit = failed(RuntimeException("network down")))

        val result = repo.claim("u1", "ryan", current = null)

        assertTrue(result.isFailure)
        assertEquals("network down", result.exceptionOrNull()?.message)
    }

    // ── search ──────────────────────────────────────────────────────────

    @Test
    fun search_emptyAfterNormalizing_returnsEmptyWithoutQuery() = runTest {
        val result = repo.search(" @ ")

        assertEquals(emptyList<UserSearchResult>(), result.getOrNull())
        verify(exactly = 0) { usernamesCol.orderBy(any<FieldPath>()) }
    }

    private fun stubSearchQuery(prefix: String, docs: List<DocumentSnapshot>) {
        val q1 = mockk<Query>()
        val q2 = mockk<Query>()
        val q3 = mockk<Query>()
        val q4 = mockk<Query>()
        val qs = mockk<QuerySnapshot>()
        every { usernamesCol.orderBy(any<FieldPath>()) } returns q1
        every { q1.startAt(prefix) } returns q2
        every { q2.endAt(prefix + "") } returns q3
        every { q3.limit(10L) } returns q4
        every { q4.get() } returns Tasks.forResult(qs)
        every { qs.documents } returns docs
    }

    @Test
    fun search_normalizesPrefixAndJoinsUserProfiles() = runTest {
        stubSearchQuery(
            "ali",
            listOf(
                snap(id = "alice", strings = mapOf("uid" to "u1")),
                snap(id = "alina", strings = mapOf("uid" to "u2")),
                snap(id = "alex_no_uid", strings = emptyMap())
            )
        )
        val u1 = userDoc("u1")
        every { u1.get() } returns Tasks.forResult(
            snap(strings = mapOf("displayName" to "Alice A", "photoUrl" to "http://a"))
        )
        val u2 = userDoc("u2")
        every { u2.get() } returns Tasks.forResult(
            snap(strings = mapOf("displayName" to "  ", "photoUrl" to ""))
        )

        // "@Ali" is normalised to "ali".
        val result = repo.search("@Ali").getOrThrow()

        assertEquals(
            listOf(
                UserSearchResult(uid = "u1", username = "alice", displayName = "Alice A", photoUrl = "http://a"),
                // Blank display name falls back to the handle; blank photo becomes null.
                UserSearchResult(uid = "u2", username = "alina", displayName = "alina", photoUrl = null)
            ),
            result
        )
    }

    @Test
    fun search_profileReadFails_stillReturnsHandleWithFallbackName() = runTest {
        stubSearchQuery("bob", listOf(snap(id = "bobby", strings = mapOf("uid" to "u9"))))
        val u9 = userDoc("u9")
        every { u9.get() } returns failed(RuntimeException("denied"))

        val result = repo.search("bob").getOrThrow()

        assertEquals(listOf(UserSearchResult("u9", "bobby", "bobby", null)), result)
    }

    @Test
    fun search_queryFailure_isReturnedAsFailure() = runTest {
        val q1 = mockk<Query>()
        val q2 = mockk<Query>()
        val q3 = mockk<Query>()
        val q4 = mockk<Query>()
        every { usernamesCol.orderBy(any<FieldPath>()) } returns q1
        every { q1.startAt("bob") } returns q2
        every { q2.endAt("bob") } returns q3
        every { q3.limit(10L) } returns q4
        every { q4.get() } returns failed(RuntimeException("offline"))

        val result = repo.search("bob")

        assertTrue(result.isFailure)
        assertFalse(result.getOrNull() != null)
    }
}
