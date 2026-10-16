package com.example.chorevo.firebase

import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.testing.MainDispatcherRule
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.AuthCredential
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
        val snapshot = mockk<DocumentSnapshot>()

        every { authResult.user } returns user
        every { user.uid } returns "uid123"
        every { user.email } returns "a@b.com"
        every { user.photoUrl } returns null

        every {
            auth.createUserWithEmailAndPassword("a@b.com", "pass123")
        } returns Tasks.forResult(authResult)

        val usersCollection = mockk<CollectionReference>()
        val docRef = mockk<DocumentReference>()
        val leaderboardCollection = mockk<CollectionReference>()
        val leaderboardDoc = mockk<DocumentReference>()

        every { db.collection("users") } returns usersCollection
        every { db.collection("leaderboardStats") } returns leaderboardCollection
        every { usersCollection.document("uid123") } returns docRef
        every { leaderboardCollection.document("uid123") } returns leaderboardDoc
        every {
            docRef.set(any<Map<String, Any?>>(), SetOptions.merge())
        } returns Tasks.forResult(null)
        every { docRef.get() } returns Tasks.forResult(snapshot)
        every { snapshot.getString("displayName") } returns "Mark"
        every { snapshot.getString("photoUrl") } returns null
        every { snapshot.getString("username") } returns null
        every {
            leaderboardDoc.set(any<Map<String, Any?>>(), SetOptions.merge())
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
        verify(exactly = 1) { db.collection("leaderboardStats") }
        verify(exactly = 1) { usersCollection.document("uid123") }
        verify(exactly = 1) {
            docRef.set(
                match<Map<String, Any?>> { m: Map<String, Any?> ->
                    m["displayName"] == "Mark"
                },
                SetOptions.merge()
            )
        }
        verify(exactly = 1) { docRef.get() }
        verify(exactly = 1) { leaderboardCollection.document("uid123") }
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
        val leaderboardCollection = mockk<CollectionReference>()
        val leaderboardDoc = mockk<DocumentReference>()

        every { authResult.user } returns user
        every { user.uid } returns "uid999"
        every { user.email } returns "real@user.com"
        every { user.photoUrl } returns null

        every {
            auth.signInWithEmailAndPassword("x@y.com", "pw")
        } returns Tasks.forResult(authResult)

        every { db.collection("users") } returns usersCollection
        every { db.collection("leaderboardStats") } returns leaderboardCollection
        every { usersCollection.document("uid999") } returns docRef
        every { leaderboardCollection.document("uid999") } returns leaderboardDoc

        every { docRef.get() } returns Tasks.forResult(snapshot)
        every { snapshot.getString("displayName") } returns "  Devin  "
        every { snapshot.getString("photoUrl") } returns null
        every { snapshot.getString("username") } returns null

        every {
            docRef.set(any<Map<String, Any?>>(), SetOptions.merge())
        } returns Tasks.forResult(null)
        every {
            leaderboardDoc.set(any<Map<String, Any?>>(), SetOptions.merge())
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
        verify(exactly = 1) { db.collection("users") }
        verify(exactly = 1) { db.collection("leaderboardStats") }
        verify(exactly = 1) { usersCollection.document("uid999") }
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
        verify(exactly = 1) { leaderboardCollection.document("uid999") }
    }

    @Test
    fun signInWithGoogle_success_syncsFirebaseUserIntoFirestore() = runTest {
        val auth = mockk<FirebaseAuth>()
        val db = mockk<FirebaseFirestore>()
        val repo = makeRepo(auth, db)

        val authResult = mockk<AuthResult>()
        val user = mockk<FirebaseUser>()
        val usersCollection = mockk<CollectionReference>()
        val docRef = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()
        val leaderboardCollection = mockk<CollectionReference>()
        val leaderboardDoc = mockk<DocumentReference>()

        every { authResult.user } returns user
        every { user.uid } returns "google123"
        every { user.email } returns "google@example.com"
        every { user.displayName } returns "Google User"
        every { user.photoUrl } returns null

        every {
            auth.signInWithCredential(any<AuthCredential>())
        } returns Tasks.forResult(authResult)

        every { db.collection("users") } returns usersCollection
        every { db.collection("leaderboardStats") } returns leaderboardCollection
        every { usersCollection.document("google123") } returns docRef
        every { leaderboardCollection.document("google123") } returns leaderboardDoc
        every { docRef.set(any<Map<String, Any?>>(), SetOptions.merge()) } returns Tasks.forResult(null)
        every { docRef.get() } returns Tasks.forResult(snapshot)
        every { snapshot.getString("displayName") } returns "Google User"
        every { snapshot.getString("photoUrl") } returns null
        every { snapshot.getString("username") } returns null
        every { leaderboardDoc.set(any<Map<String, Any?>>(), SetOptions.merge()) } returns Tasks.forResult(null)

        val result = repo.signInWithGoogle("id-token-123")

        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals("google123", result.getOrThrow().uid)
        assertEquals("google@example.com", result.getOrThrow().email)
        assertEquals("Google User", result.getOrThrow().displayName)

        verify(exactly = 1) { auth.signInWithCredential(any<AuthCredential>()) }
        verify(exactly = 1) { db.collection("users") }
        verify(exactly = 1) { db.collection("leaderboardStats") }
    }

    /** Wires a signed-in Google user whose stored profile has [storedName] / [storedPhoto]. */
    private fun googleSignInFixture(
        storedName: String?,
        storedPhoto: String?,
        googleName: String?,
        googlePhoto: String?
    ): Triple<FirebaseAuthRepository, DocumentReference, DocumentReference> {
        val auth = mockk<FirebaseAuth>()
        val db = mockk<FirebaseFirestore>()
        val authResult = mockk<AuthResult>()
        val user = mockk<FirebaseUser>()
        val usersCollection = mockk<CollectionReference>()
        val docRef = mockk<DocumentReference>()
        val snapshot = mockk<DocumentSnapshot>()
        val leaderboardCollection = mockk<CollectionReference>()
        val leaderboardDoc = mockk<DocumentReference>()

        every { authResult.user } returns user
        every { user.uid } returns "g1"
        every { user.email } returns "g@example.com"
        every { user.displayName } returns googleName
        every { user.photoUrl } returns googlePhoto?.let { url -> mockk<android.net.Uri> { every { this@mockk.toString() } returns url } }
        every { auth.signInWithCredential(any<AuthCredential>()) } returns Tasks.forResult(authResult)
        every { db.collection("users") } returns usersCollection
        every { db.collection("leaderboardStats") } returns leaderboardCollection
        every { usersCollection.document("g1") } returns docRef
        every { leaderboardCollection.document("g1") } returns leaderboardDoc
        every { docRef.get() } returns Tasks.forResult(snapshot)
        every { snapshot.getString("displayName") } returns storedName
        every { snapshot.getString("photoUrl") } returns storedPhoto
        every { snapshot.getString("username") } returns null
        every { docRef.set(any<Map<String, Any?>>(), SetOptions.merge()) } returns Tasks.forResult(null)
        every { leaderboardDoc.set(any<Map<String, Any?>>(), SetOptions.merge()) } returns Tasks.forResult(null)
        return Triple(makeRepo(auth, db), docRef, leaderboardDoc)
    }

    @Test
    fun signIn_keepsNameAndPhotoTheUserSetInTheApp() = runTest {
        val (repo, docRef, _) = googleSignInFixture(
            storedName = "Stage Name",
            storedPhoto = "https://custom/photo.jpg",
            googleName = "Google Name",
            googlePhoto = "https://google/photo.jpg"
        )

        val profile = repo.signInWithGoogle("token").getOrThrow()

        assertEquals("Stage Name", profile.displayName)
        assertEquals("https://custom/photo.jpg", profile.photoUrl)
        verify(exactly = 1) {
            docRef.set(
                match<Map<String, Any?>> {
                    it["displayName"] == "Stage Name" && it["photoUrl"] == "https://custom/photo.jpg"
                },
                SetOptions.merge()
            )
        }
    }

    @Test
    fun signIn_neverWritesNullNameOrPhoto() = runTest {
        val (repo, docRef, _) = googleSignInFixture(
            storedName = null,
            storedPhoto = null,
            googleName = null,
            googlePhoto = null
        )

        val profile = repo.signInWithGoogle("token").getOrThrow()

        assertNull(profile.displayName)
        verify(exactly = 1) {
            docRef.set(
                match<Map<String, Any?>> { !it.containsKey("displayName") && !it.containsKey("photoUrl") },
                SetOptions.merge()
            )
        }
    }

    @Test
    fun signIn_fillsMissingNameAndPhotoFromProvider() = runTest {
        val (repo, _, _) = googleSignInFixture(
            storedName = null,
            storedPhoto = null,
            googleName = "Google Name",
            googlePhoto = "https://google/photo.jpg"
        )

        val profile = repo.signInWithGoogle("token").getOrThrow()

        assertEquals("Google Name", profile.displayName)
        assertEquals("https://google/photo.jpg", profile.photoUrl)
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
