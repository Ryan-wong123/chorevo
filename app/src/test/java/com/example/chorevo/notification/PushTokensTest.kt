package com.example.chorevo.notification

import android.util.Log
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PushTokensTest {

    private lateinit var messaging: FirebaseMessaging
    private lateinit var firestore: FirebaseFirestore
    private lateinit var pushTokens: PushTokens

    private lateinit var tokenDoc: DocumentReference
    private lateinit var tokensCollection: CollectionReference
    private lateinit var usersCollection: CollectionReference
    private lateinit var userDoc: DocumentReference
    private lateinit var setTask: Task<Void>

    private val savedData = slot<Any>()
    private val saveFailureListener = slot<OnFailureListener>()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

        messaging = mockk(relaxed = true)
        firestore = mockk()
        usersCollection = mockk()
        userDoc = mockk()
        tokensCollection = mockk()
        tokenDoc = mockk()
        setTask = mockk(relaxed = true)

        every { firestore.collection("users") } returns usersCollection
        every { usersCollection.document("uid1") } returns userDoc
        every { userDoc.collection("fcmTokens") } returns tokensCollection
        every { tokensCollection.document("tok-123") } returns tokenDoc
        every { tokenDoc.set(capture(savedData)) } returns setTask
        every { setTask.addOnFailureListener(capture(saveFailureListener)) } returns setTask

        pushTokens = PushTokens(messaging, firestore)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── save ──────────────────────────────────────────────────────────────────

    @Test
    fun save_writesTokenDocumentUnderUsersUidFcmTokens() {
        val before = System.currentTimeMillis()

        pushTokens.save("uid1", "tok-123")

        val after = System.currentTimeMillis()
        val data = savedData.captured as Map<*, *>
        assertEquals("tok-123", data["token"])
        assertEquals("android", data["platform"])
        val updatedAt = data["updatedAt"] as Long
        assertTrue(updatedAt in before..after)
        verify(exactly = 1) { tokensCollection.document("tok-123") }
    }

    @Test
    fun save_failureListener_logsWarningWithoutThrowing() {
        pushTokens.save("uid1", "tok-123")

        saveFailureListener.captured.onFailure(RuntimeException("denied"))

        verify(exactly = 1) { Log.w("PushTokens", "Couldn't save push token", any<Throwable>()) }
    }

    @Test
    fun save_firestoreThrows_isSwallowed() {
        every { firestore.collection("users") } throws IllegalStateException("firestore down")

        pushTokens.save("uid1", "tok-123")

        verify(exactly = 0) { tokenDoc.set(any<Any>()) }
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    fun register_savesTokenOnceItArrives() {
        val tokenTask = mockk<Task<String>>(relaxed = true)
        every { messaging.token } returns tokenTask
        every { tokenTask.addOnSuccessListener(any<OnSuccessListener<String>>()) } answers {
            firstArg<OnSuccessListener<String>>().onSuccess("tok-123")
            tokenTask
        }

        pushTokens.register("uid1")

        verify(exactly = 1) { tokenDoc.set(any<Any>()) }
        val data = savedData.captured as Map<*, *>
        assertEquals("tok-123", data["token"])
    }

    @Test
    fun register_tokenNotYetAvailable_savesNothing() {
        val tokenTask = mockk<Task<String>>(relaxed = true)
        every { messaging.token } returns tokenTask
        every { tokenTask.addOnSuccessListener(any<OnSuccessListener<String>>()) } returns tokenTask

        pushTokens.register("uid1")

        verify(exactly = 0) { tokenDoc.set(any<Any>()) }
    }

    @Test
    fun register_tokenFetchFails_logsWarning() {
        val tokenTask = mockk<Task<String>>(relaxed = true)
        every { messaging.token } returns tokenTask
        every { tokenTask.addOnSuccessListener(any<OnSuccessListener<String>>()) } returns tokenTask
        every { tokenTask.addOnFailureListener(any<OnFailureListener>()) } answers {
            firstArg<OnFailureListener>().onFailure(RuntimeException("no play services"))
            tokenTask
        }

        pushTokens.register("uid1")

        verify(exactly = 1) { Log.w("PushTokens", "No push token", any<Throwable>()) }
        verify(exactly = 0) { tokenDoc.set(any<Any>()) }
    }

    @Test
    fun register_messagingUnavailable_isSwallowedAndLogged() {
        every { messaging.token } throws IllegalStateException("FirebaseApp not initialized")

        pushTokens.register("uid1")

        verify(exactly = 1) { Log.w("PushTokens", "Push unavailable", any<Throwable>()) }
    }

    // ── unregister ────────────────────────────────────────────────────────────

    @Test
    fun unregister_deletesTheDeviceToken() {
        pushTokens.unregister()

        verify(exactly = 1) { messaging.deleteToken() }
    }

    @Test
    fun unregister_deleteThrows_isSwallowed() {
        every { messaging.deleteToken() } throws IllegalStateException("not initialised")

        pushTokens.unregister()

        verify(exactly = 1) { messaging.deleteToken() }
    }
}
