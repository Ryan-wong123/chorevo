package com.example.chorevo.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [BootReceiver] does its work on Dispatchers.IO and calls pending.finish() last,
 * so each test waits for finish() (with a timeout) before checking what was scheduled.
 */
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var scheduler: ReminderScheduler
    private lateinit var prefs: NotificationPreferencesDataStore
    private lateinit var pending: BroadcastReceiver.PendingResult
    private lateinit var receiver: BootReceiver

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        pending = mockk(relaxed = true)

        val entryPoint = mockk<NotificationEntryPoint>()
        every { entryPoint.reminderScheduler() } returns scheduler
        every { entryPoint.notificationPreferences() } returns prefs
        mockkObject(NotificationEntryPoint.Companion)
        every { NotificationEntryPoint.from(any()) } returns entryPoint

        every { prefs.pushEnabledFlow } returns flowOf(true)
        every { prefs.notificationHourFlow } returns flowOf(7)
        every { prefs.notificationMinuteFlow } returns flowOf(30)

        receiver = spyk(BootReceiver())
        every { receiver.goAsync() } returns pending
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun intentWithAction(action: String?): Intent {
        val intent = mockk<Intent>()
        every { intent.action } returns action
        return intent
    }

    @Test
    fun onReceive_bootCompleted_pushEnabled_reschedulesDailyAtStoredTime() {
        receiver.onReceive(context, intentWithAction(Intent.ACTION_BOOT_COMPLETED))

        verify(timeout = 5000) { pending.finish() }
        verify(exactly = 1) { scheduler.scheduleNextDaily(7, 30) }
    }

    @Test
    fun onReceive_packageReplaced_pushEnabled_reschedulesDaily() {
        receiver.onReceive(context, intentWithAction(Intent.ACTION_MY_PACKAGE_REPLACED))

        verify(timeout = 5000) { pending.finish() }
        verify(exactly = 1) { scheduler.scheduleNextDaily(7, 30) }
    }

    @Test
    fun onReceive_pushDisabled_doesNotSchedule_butStillFinishesPendingResult() {
        every { prefs.pushEnabledFlow } returns flowOf(false)

        receiver.onReceive(context, intentWithAction(Intent.ACTION_BOOT_COMPLETED))

        verify(timeout = 5000) { pending.finish() }
        verify(exactly = 0) { scheduler.scheduleNextDaily(any(), any()) }
    }

    @Test
    fun onReceive_pushEnabled_usesDifferentStoredTime() {
        every { prefs.notificationHourFlow } returns flowOf(21)
        every { prefs.notificationMinuteFlow } returns flowOf(5)

        receiver.onReceive(context, intentWithAction(Intent.ACTION_BOOT_COMPLETED))

        verify(timeout = 5000) { pending.finish() }
        verify(exactly = 1) { scheduler.scheduleNextDaily(21, 5) }
    }

    @Test
    fun onReceive_unrelatedAction_isIgnored() {
        receiver.onReceive(context, intentWithAction("android.intent.action.TIME_SET"))

        verify(exactly = 0) { receiver.goAsync() }
        verify(exactly = 0) { scheduler.scheduleNextDaily(any(), any()) }
    }

    @Test
    fun onReceive_nullIntent_isIgnored() {
        receiver.onReceive(context, null)

        verify(exactly = 0) { receiver.goAsync() }
        verify(exactly = 0) { scheduler.scheduleNextDaily(any(), any()) }
    }

    @Test
    fun onReceive_intentWithoutAction_isIgnored() {
        receiver.onReceive(context, intentWithAction(null))

        verify(exactly = 0) { receiver.goAsync() }
        verify(exactly = 0) { scheduler.scheduleNextDaily(any(), any()) }
    }
}
