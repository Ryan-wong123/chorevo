package com.example.chorevo.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Plain-JVM tests for [ReminderScheduler]. AlarmManager, PendingIntent and Intent
 * are mocked; the scheduling decisions (next trigger time, exact alarm, request
 * codes, extras) are asserted through those mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var prefs: NotificationPreferencesDataStore
    private lateinit var pendingIntent: PendingIntent
    private lateinit var scheduler: ReminderScheduler
    private lateinit var originalTimeZone: TimeZone

    private val triggerSlot = slot<Long>()

    private val immutableUpdateFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    @Before
    fun setup() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Singapore"))

        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0

        context = mockk(relaxed = true)
        alarmManager = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        pendingIntent = mockk(relaxed = true)

        every { context.getSystemService(Context.ALARM_SERVICE) } returns alarmManager
        every { alarmManager.canScheduleExactAlarms() } returns true
        every { alarmManager.setExactAndAllowWhileIdle(any(), capture(triggerSlot), any()) } just Runs

        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns pendingIntent

        // Intent is an Android stub class: its constructor and putExtra must not hit the stub bodies.
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } returns mockk<Intent>()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } returns mockk<Intent>()

        scheduler = ReminderScheduler(context, prefs)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
        unmockkAll()
    }

    private fun expectedNext(zone: ZoneId, hour: Int, minute: Int, now: ZonedDateTime): Long {
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    // ── next-alarm time ───────────────────────────────────────────────────────

    @Test
    fun scheduleNextDaily_matchesNextOccurrenceOfWallClockTime() {
        val zone = ZoneId.of("Asia/Singapore")
        val before = ZonedDateTime.now(zone)
        scheduler.scheduleNextDaily(7, 30)
        val after = ZonedDateTime.now(zone)

        val trigger = triggerSlot.captured
        // Either clock reading may have been the one in use when the scheduler ran.
        assertTrue(
            trigger == expectedNext(zone, 7, 30, before) || trigger == expectedNext(zone, 7, 30, after)
        )
    }

    @Test
    fun scheduleNextDaily_isAlwaysInTheFutureAndWithinOneDay() {
        val before = System.currentTimeMillis()
        scheduler.scheduleNextDaily(0, 0)
        val after = System.currentTimeMillis()

        val trigger = triggerSlot.captured
        assertTrue(trigger > before)
        assertTrue(trigger <= after + 24L * 60 * 60 * 1000)
    }

    @Test
    fun scheduleNextDaily_timeAlreadyPassedToday_rollsOverToTomorrow() {
        val zone = ZoneId.of("Asia/Singapore")
        val oneMinuteAgo = ZonedDateTime.now(zone).minusMinutes(1)
        val before = System.currentTimeMillis()

        scheduler.scheduleNextDaily(oneMinuteAgo.hour, oneMinuteAgo.minute)

        val delta = triggerSlot.captured - before
        // Just under 24 hours away, never "today".
        assertTrue("delta=$delta", delta > 23L * 60 * 60 * 1000)
        assertTrue("delta=$delta", delta <= 24L * 60 * 60 * 1000)
    }

    @Test
    fun scheduleNextDaily_timeAFewMinutesAhead_firesWithinMinutes() {
        val zone = ZoneId.of("Asia/Singapore")
        val soon = ZonedDateTime.now(zone).plusMinutes(2)
        val before = System.currentTimeMillis()

        scheduler.scheduleNextDaily(soon.hour, soon.minute)

        val delta = triggerSlot.captured - before
        assertTrue("delta=$delta", delta > 0)
        assertTrue("delta=$delta", delta <= 3L * 60 * 1000)
    }

    @Test
    fun scheduleNextDaily_triggerHasZeroSecondsAndMilliseconds() {
        scheduler.scheduleNextDaily(18, 45)

        val trigger = triggerSlot.captured
        assertEquals(0L, trigger % 60_000L)
    }

    // ── time zone handling ────────────────────────────────────────────────────

    private fun assertFiresAtLocalWallClock(zoneId: String, hour: Int, minute: Int) {
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
        val before = System.currentTimeMillis()

        scheduler.scheduleNextDaily(hour, minute)

        val trigger = triggerSlot.captured
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(trigger), ZoneId.of(zoneId))
        assertEquals("hour in $zoneId", hour, local.hour)
        assertEquals("minute in $zoneId", minute, local.minute)
        assertEquals(0, local.second)
        assertTrue(trigger > before)
        // 25h allows for a daylight-saving change in between.
        assertTrue(trigger <= before + 25L * 60 * 60 * 1000)
    }

    @Test
    fun scheduleNextDaily_usesPhoneTimeZone_singapore() {
        assertFiresAtLocalWallClock("Asia/Singapore", 6, 45)
    }

    @Test
    fun scheduleNextDaily_usesPhoneTimeZone_newYork() {
        assertFiresAtLocalWallClock("America/New_York", 6, 45)
    }

    @Test
    fun scheduleNextDaily_usesPhoneTimeZone_auckland() {
        assertFiresAtLocalWallClock("Pacific/Auckland", 21, 15)
    }

    @Test
    fun scheduleNextDaily_halfHourOffsetZone_kolkata() {
        assertFiresAtLocalWallClock("Asia/Kolkata", 9, 0)
    }

    @Test
    fun scheduleNextDaily_sameWallClockTimeIsDifferentInstantInDifferentZones() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Singapore"))
        scheduler.scheduleNextDaily(9, 0)
        val singapore = ZonedDateTime.ofInstant(Instant.ofEpochMilli(triggerSlot.captured), ZoneOffset.UTC)

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        scheduler.scheduleNextDaily(9, 0)
        val kolkata = ZonedDateTime.ofInstant(Instant.ofEpochMilli(triggerSlot.captured), ZoneOffset.UTC)

        // 09:00 in UTC+8 is 01:00 UTC; 09:00 in UTC+5:30 is 03:30 UTC.
        assertEquals(1, singapore.hour)
        assertEquals(0, singapore.minute)
        assertEquals(3, kolkata.hour)
        assertEquals(30, kolkata.minute)
    }

    // ── alarm type, intent and request codes ──────────────────────────────────

    @Test
    fun scheduleNextDaily_usesExactWakeupAlarmWithDailyPendingIntent() {
        scheduler.scheduleNextDaily(8, 0)

        verify(exactly = 1) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, any(), pendingIntent)
        }
        verify(exactly = 0) { alarmManager.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun scheduleNextDaily_dailyIntentCarriesChosenTimeForRearming() {
        scheduler.scheduleNextDaily(7, 5)

        verify { anyConstructed<Intent>().putExtra("notification_type", "daily") }
        verify { anyConstructed<Intent>().putExtra(ReminderScheduler.EXTRA_HOUR, 7) }
        verify { anyConstructed<Intent>().putExtra(ReminderScheduler.EXTRA_MINUTE, 5) }
    }

    @Test
    fun scheduleNextDaily_usesRequestCode2001_updateCurrentImmutable() {
        scheduler.scheduleNextDaily(8, 0)

        verify { PendingIntent.getBroadcast(context, 2001, any(), immutableUpdateFlags) }
    }

    @Test
    fun canUseExactAlarms_isTrueWhenAlarmManagerAllows() {
        assertTrue(scheduler.canUseExactAlarms())
    }

    // ── rescheduleFromPreferences / cancel ────────────────────────────────────

    @Test
    fun rescheduleFromPreferences_pushDisabled_cancelsDailyAndDoesNotSchedule() = runTest {
        every { prefs.pushEnabledFlow } returns flowOf(false)
        every { prefs.notificationTimeFlow } returns flowOf(7 to 15)

        scheduler.rescheduleFromPreferences()

        verify(exactly = 1) { alarmManager.cancel(pendingIntent) }
        verify(exactly = 0) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify { PendingIntent.getBroadcast(context, 2001, any(), immutableUpdateFlags) }
    }

    @Test
    fun rescheduleFromPreferences_pushEnabled_schedulesAtStoredTime() = runTest {
        val zone = ZoneId.of("Asia/Singapore")
        every { prefs.pushEnabledFlow } returns flowOf(true)
        every { prefs.notificationTimeFlow } returns flowOf(7 to 15)

        val before = ZonedDateTime.now(zone)
        scheduler.rescheduleFromPreferences()
        val after = ZonedDateTime.now(zone)

        val trigger = triggerSlot.captured
        assertTrue(
            trigger == expectedNext(zone, 7, 15, before) || trigger == expectedNext(zone, 7, 15, after)
        )
        verify { anyConstructed<Intent>().putExtra(ReminderScheduler.EXTRA_HOUR, 7) }
        verify { anyConstructed<Intent>().putExtra(ReminderScheduler.EXTRA_MINUTE, 15) }
        verify(exactly = 0) { alarmManager.cancel(any<PendingIntent>()) }
    }

    @Test
    fun cancelDaily_cancelsTheDailyPendingIntent() {
        scheduler.cancelDaily()

        verify(exactly = 1) { alarmManager.cancel(pendingIntent) }
        verify { PendingIntent.getBroadcast(context, 2001, any(), immutableUpdateFlags) }
    }

    @Test
    fun cancelAll_cancelsDailyAndBothTestAlarms() {
        scheduler.cancelAll()

        verify(exactly = 3) { alarmManager.cancel(pendingIntent) }
        verify { PendingIntent.getBroadcast(context, 2001, any(), immutableUpdateFlags) }
        verify { PendingIntent.getBroadcast(context, 2005, any(), immutableUpdateFlags) }
        verify { PendingIntent.getBroadcast(context, 2060, any(), immutableUpdateFlags) }
    }

    // ── repeating test alarms ─────────────────────────────────────────────────

    @Test
    fun scheduleRepeatingTestInMinutes_firesAfterTheInterval_andCancelsPreviousFirst() {
        val before = System.currentTimeMillis()
        scheduler.scheduleRepeatingTestInMinutes(5)
        val after = System.currentTimeMillis()

        val trigger = triggerSlot.captured
        assertTrue(trigger >= before + 5 * 60_000L)
        assertTrue(trigger <= after + 5 * 60_000L)
        verify(exactly = 1) { alarmManager.cancel(pendingIntent) }
        verify(exactly = 1) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, any(), pendingIntent)
        }
    }

    @Test
    fun scheduleRepeatingTestInMinutes_intentCarriesTypeAndInterval() {
        scheduler.scheduleRepeatingTestInMinutes(5)

        verify { anyConstructed<Intent>().putExtra("notification_type", "test") }
        verify { anyConstructed<Intent>().putExtra("test_minutes", 5) }
    }

    @Test
    fun scheduleRepeatingTestInMinutes_requestCodes_perInterval() {
        scheduler.scheduleRepeatingTestInMinutes(5)
        scheduler.scheduleRepeatingTestInMinutes(60)
        scheduler.scheduleRepeatingTestInMinutes(15)

        verify { PendingIntent.getBroadcast(context, 2005, any(), immutableUpdateFlags) }
        verify { PendingIntent.getBroadcast(context, 2060, any(), immutableUpdateFlags) }
        verify { PendingIntent.getBroadcast(context, 3015, any(), immutableUpdateFlags) }
    }

    @Test
    fun startRepeatingTestAlarms_schedulesFiveAndSixtyMinuteAlarms() {
        val before = System.currentTimeMillis()
        scheduler.startRepeatingTestAlarms()

        verify(exactly = 2) { alarmManager.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify { PendingIntent.getBroadcast(context, 2005, any(), immutableUpdateFlags) }
        verify { PendingIntent.getBroadcast(context, 2060, any(), immutableUpdateFlags) }
        // The last scheduled one is the 60-minute alarm.
        assertTrue(triggerSlot.captured >= before + 60 * 60_000L)
    }

    @Test
    fun cancelRepeatingTestAlarms_cancelsFiveAndSixtyMinuteAlarmsOnly() {
        scheduler.cancelRepeatingTestAlarms()

        verify(exactly = 2) { alarmManager.cancel(pendingIntent) }
        verify { PendingIntent.getBroadcast(context, 2005, any(), immutableUpdateFlags) }
        verify { PendingIntent.getBroadcast(context, 2060, any(), immutableUpdateFlags) }
        verify(exactly = 0) { PendingIntent.getBroadcast(context, 2001, any(), any()) }
    }

    @Test
    fun companionDefaults_areNineAm() {
        assertEquals(9, ReminderScheduler.DEFAULT_HOUR)
        assertEquals(0, ReminderScheduler.DEFAULT_MINUTE)
        assertEquals("hour", ReminderScheduler.EXTRA_HOUR)
        assertEquals("minute", ReminderScheduler.EXTRA_MINUTE)
    }
}
