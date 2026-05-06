package com.example.chorevo.firebase

import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.testing.MainDispatcherRule
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseAuthRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @After
    fun tearDown() {
        unmockkAll()
    }
    private fun makeRepo(
        auth: FirebaseAuth,
        db: FirebaseFirestore,
        storage: FirebaseStorage = mockk(relaxed = true)
    ): FirebaseAuthRepository {
        return FirebaseAuthRepository(auth, db, storage)
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun signUp_success_returnsUserProfile_andWritesFirestore() = runTest {
        val auth = mockk<FirebaseAuth>()
        val db = mockk<FirebaseFirestore>()
        val repo = makeRepo(auth, db)

        val authResult = mockk<AuthResult>()
        val user = mockk<FirebaseUser>()

        every { authResult.user } returns user
        every { user.uid } returns "uid123"

        every {
            auth.createUserWithEmailAndPassword("a@b.com", "pass123")
        } returns Tasks.forResult(authResult)

        val usersCollection = mockk<CollectionReference>()
        val docRef = mockk<DocumentReference>()

        every { db.collection("users") } returns usersCollection
        every { usersCollection.document("uid123") } returns docRef
        every {
            docRef.set(any<Map<String, Any?>>(), SetOptions.merge())
        } returns Tasks.forResult(null)

        val result = repo.signUp(
            email = "a@b.com",
            password = "pass123",
            displayName = "  Mark  "
        )

        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals("uid123", profile.uid)
        assertEquals("a@b.com", profile.email)
        assertEquals("Mark", profile.displayName)
        assertEquals(UserRole.USER, profile.role)

        verify(exactly = 1) {
            auth.createUserWithEmailAndPassword("a@b.com", "pass123")
        }
        verify(exactly = 1) { db.collection("users") }
        verify(exactly = 1) { usersCollection.document("uid123") }
        verify(exactly = 1) {
            docRef.set(
                match<Map<String, Any?>> { m: Map<String, Any?> ->
                    m["displayName"] == "Mark"
                },
                SetOptions.merge()
            )
        }
    }

    @Test
    fun signUp_failure_returnsFailureResult() = runTest {
        val auth = mockk<FirebaseAuth>()
        val db = mockk<FirebaseFirestore>()
        val repo = makeRepo(auth, db)

        every {
            auth.createUserWithEmailAndPassword(any(), any())
        } returns Tasks.forException(IllegalArgumentException("bad email"))

        val result = repo.signUp(
            email = "bad",
            password = "pass123",
            displayName = "Mark"
        )

        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertEquals("bad email", result.exceptionOrNull()?.message)
    }

    @Test
    fun signIn_success_readsDisplayName_updatesLastSeen_returnsProfile() = runTest {
        val auth = mockk<FirebaseAuth>()
        val db = mockk<FirebaseFirestore>()
        val repo = makeRepo(auth, db)

        val authResult = mockk<AuthResult>()
        val user = mockk<FirebaseUser>()
        val usersCollection = mockk<CollectionReference>()
        val docRef = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()

        every { authResult.user } returns user
        every { user.uid } returns "uid999"
        every { user.email } returns "real@user.com"

        every {
            auth.signInWithEmailAndPassword("x@y.com", "pw")
        } returns Tasks.forResult(authResult)

        every { db.collection("users") } returns usersCollection
        every { usersCollection.document("uid999") } returns docRef

        every { docRef.get() } returns Tasks.forResult(snapshot)
        every { snapshot.getString("displayName") } returns "  Devin  "
        every { snapshot.getString("photoUrl") } returns null

        every {
            docRef.set(any<Map<String, Any?>>(), SetOptions.merge())
        } returns Tasks.forResult(null)

        val result = repo.signIn("x@y.com", "pw")

        advanceUntilIdle()

        assertTrue(
            "Expected signIn() to succeed, but got: ${result.exceptionOrNull()}",
            result.isSuccess
        )

        val profile = result.getOrThrow()
        assertEquals("uid999", profile.uid)
        assertEquals("real@user.com", profile.email)
        assertEquals("Devin", profile.displayName)
        assertEquals(UserRole.USER, profile.role)
        assertNull(profile.photoUrl)

        verify(exactly = 1) { auth.signInWithEmailAndPassword("x@y.com", "pw") }
        verify(exactly = 2) { db.collection("users") }
        verify(exactly = 2) { usersCollection.document("uid999") }
        verify(exactly = 1) { docRef.get() }
        verify(exactly = 1) { snapshot.getString("displayName") }
        verify(exactly = 1) { snapshot.getString("photoUrl") }
        verify(exactly = 1) {
            docRef.set(
                match<Map<String, Any?>> { m: Map<String, Any?> ->
                    m.containsKey("lastSeen")
                },
                SetOptions.merge()
            )
        }
    }

    @Test
    fun getCurrentUserProfile_nullWhenNoCurrentUser() = runTest {
        val auth = mockk<FirebaseAuth>()
        val db = mockk<FirebaseFirestore>()
        val repo = makeRepo(auth, db)

        every { auth.currentUser } returns null

        val profile = repo.getCurrentUserProfile()

        advanceUntilIdle()

        assertNull(profile)
    }

    @Test
    fun signOut_callsFirebaseAuthSignOut() {
        val auth = mockk<FirebaseAuth>(relaxed = true)
        val db = mockk<FirebaseFirestore>()
        val repo = makeRepo(auth, db)

        repo.signOut()

        verify(exactly = 1) { auth.signOut() }
    }
}