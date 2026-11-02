package com.example.chorevo.notification

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Only [ChorevoMessagingService.onNewToken] is covered; onMessageReceived builds
 * real notifications and needs the Android runtime.
 */
class ChorevoMessagingServiceTest {

    private lateinit var auth: FirebaseAuth
    private lateinit var pushTokens: PushTokens
    private lateinit var service: ChorevoMessagingService

    @Before
    fun setup() {
        auth = mockk()
        pushTokens = mockk(relaxed = true)
        service = ChorevoMessagingService()
        service.auth = auth
        service.pushTokens = pushTokens
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun onNewToken_signedIn_savesTokenForCurrentUser() {
        val user = mockk<FirebaseUser>()
        every { user.uid } returns "uid1"
        every { auth.currentUser } returns user

        service.onNewToken("fresh-token")

        verify(exactly = 1) { pushTokens.save("uid1", "fresh-token") }
    }

    @Test
    fun onNewToken_signedOut_doesNotSaveAnything() {
        every { auth.currentUser } returns null

        service.onNewToken("fresh-token")

        verify(exactly = 0) { pushTokens.save(any(), any()) }
    }

    @Test
    fun groupInvitationType_matchesServerPayloadValue() {
        assertEquals("group_invitation", ChorevoMessagingService.TYPE_GROUP_INVITATION)
    }
}
