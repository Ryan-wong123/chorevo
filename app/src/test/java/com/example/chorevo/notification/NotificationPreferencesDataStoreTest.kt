package com.example.chorevo.notification

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs the real DataStore against a temporary directory behind a mocked Context
 * (only applicationContext and filesDir are needed).
 *
 * The store is a process-wide singleton per file, so tests share state and only
 * assert values they have just written (never the untouched defaults).
 */
class NotificationPreferencesDataStoreTest {

    companion object {
        @ClassRule
        @JvmField
        val folder = TemporaryFolder()
    }

    private lateinit var context: Context
    private lateinit var store: NotificationPreferencesDataStore

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.filesDir } returns folder.root
        store = NotificationPreferencesDataStore(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun setPushEnabled_true_isReadBack() = runBlocking {
        store.setPushEnabled(true)

        assertTrue(store.pushEnabledFlow.first())
    }

    @Test
    fun setPushEnabled_false_isReadBack() = runBlocking {
        store.setPushEnabled(true)
        store.setPushEnabled(false)

        assertEquals(false, store.pushEnabledFlow.first())
    }

    @Test
    fun setNotificationTime_updatesHourMinuteAndPairFlows() = runBlocking {
        store.setNotificationTime(7, 45)

        assertEquals(7, store.notificationHourFlow.first())
        assertEquals(45, store.notificationMinuteFlow.first())
        assertEquals(7 to 45, store.notificationTimeFlow.first())
    }

    @Test
    fun setNotificationTime_overwritesPreviousTime() = runBlocking {
        store.setNotificationTime(7, 45)
        store.setNotificationTime(22, 5)

        assertEquals(22 to 5, store.notificationTimeFlow.first())
    }

    @Test
    fun setNotificationTime_doesNotChangePushEnabled() = runBlocking {
        store.setPushEnabled(true)

        store.setNotificationTime(8, 15)

        assertTrue(store.pushEnabledFlow.first())
    }

    @Test
    fun setPushEnabled_doesNotChangeNotificationTime() = runBlocking {
        store.setNotificationTime(6, 20)

        store.setPushEnabled(false)

        assertEquals(6 to 20, store.notificationTimeFlow.first())
    }

    @Test
    fun setThemeMode_isReadBackAndOverwritten() = runBlocking {
        store.setThemeMode("dark")
        assertEquals("dark", store.themeModeFlow.first())

        store.setThemeMode("light")
        assertEquals("light", store.themeModeFlow.first())
    }

    @Test
    fun setOnboardingSeen_marksOnboardingSeen() = runBlocking {
        store.setOnboardingSeen()

        assertTrue(store.onboardingSeenFlow.first())
    }

    @Test
    fun newInstance_seesValuesWrittenByAnotherInstance() = runBlocking {
        store.setNotificationTime(13, 37)

        val other = NotificationPreferencesDataStore(context)

        assertEquals(13 to 37, other.notificationTimeFlow.first())
    }
}
