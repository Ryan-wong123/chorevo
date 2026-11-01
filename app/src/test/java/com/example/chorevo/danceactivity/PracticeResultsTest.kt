package com.example.chorevo.danceactivity

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PracticeResultsTest {

    // In-memory stand-in for SharedPreferences, so the store runs on the plain JVM.
    private val saved = mutableMapOf<String, Float>()
    private val pending = mutableMapOf<String, Float>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val prefs = mockk<SharedPreferences>()
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        every { editor.putFloat(any(), any()) } answers {
            pending[firstArg<String>()] = secondArg<Float>()
            editor
        }
        every { editor.apply() } answers {
            saved.putAll(pending)
            pending.clear()
        }
        every { prefs.edit() } returns editor
        every { prefs.getFloat(any(), any()) } answers { saved[firstArg<String>()] ?: secondArg<Float>() }
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
    }

    private fun newStore() = PersonalBestStore(context)

    // ── gradeFor ─────────────────────────────────────────────────────────────

    @Test
    fun gradeFor_atAndAboveNinety_isS() {
        assertEquals("S", gradeFor(0.90))
        assertEquals("S", gradeFor(0.95))
        assertEquals("S", gradeFor(1.0))
    }

    @Test
    fun gradeFor_justBelowNinety_isA() {
        assertEquals("A", gradeFor(0.8999))
        assertEquals("A", gradeFor(0.80))
    }

    @Test
    fun gradeFor_betweenSixtyFiveAndEighty_isB() {
        assertEquals("B", gradeFor(0.7999))
        assertEquals("B", gradeFor(0.65))
    }

    @Test
    fun gradeFor_belowSixtyFive_isC() {
        assertEquals("C", gradeFor(0.6499))
        assertEquals("C", gradeFor(0.0))
    }

    @Test
    fun gradeFor_outOfRangeScores_stillGraded() {
        assertEquals("C", gradeFor(-0.5))
        assertEquals("S", gradeFor(1.5))
    }

    // ── PracticeResult ───────────────────────────────────────────────────────

    @Test
    fun practiceResult_copyKeepsOtherFields() {
        val result = PracticeResult("d1", "Title", 0.8, 0.9, 0.7, isPersonalBest = false)

        val best = result.copy(isPersonalBest = true)

        assertTrue(best.isPersonalBest)
        assertEquals("d1", best.danceId)
        assertEquals(0.8, best.alignment, 0.0)
        assertEquals(result.copy(isPersonalBest = true), best)
    }

    // ── PersonalBestStore ────────────────────────────────────────────────────

    @Test
    fun store_firstScore_isAlwaysPersonalBest() {
        assertTrue(newStore().recordIfBest("u1", "d1", 0.4))
    }

    @Test
    fun store_firstScoreOfZero_isPersonalBest() {
        assertTrue(newStore().recordIfBest("u1", "d1", 0.0))
    }

    @Test
    fun store_higherScore_replacesBest() {
        val store = newStore()
        store.recordIfBest("u1", "d1", 0.5)

        assertTrue(store.recordIfBest("u1", "d1", 0.7))
        assertEquals(0.7f, saved["best_u1_d1"]!!, 0.0001f)
    }

    @Test
    fun store_lowerScore_isNotBest_andKeepsPrevious() {
        val store = newStore()
        store.recordIfBest("u1", "d1", 0.8)

        assertFalse(store.recordIfBest("u1", "d1", 0.6))
        assertEquals(0.8f, saved["best_u1_d1"]!!, 0.0001f)
    }

    @Test
    fun store_equalScore_isNotBest() {
        val store = newStore()
        store.recordIfBest("u1", "d1", 0.8)

        assertFalse(store.recordIfBest("u1", "d1", 0.8))
    }

    @Test
    fun store_equalScoreAfterFloatRounding_isNotBest() {
        val store = newStore()
        store.recordIfBest("u1", "d1", 0.1 + 0.2)

        assertFalse(store.recordIfBest("u1", "d1", 0.3))
    }

    @Test
    fun store_bestsAreTrackedPerDance() {
        val store = newStore()
        store.recordIfBest("u1", "d1", 0.9)

        assertTrue(store.recordIfBest("u1", "d2", 0.3))
        assertFalse(store.recordIfBest("u1", "d1", 0.5))
    }

    @Test
    fun store_bestsAreTrackedPerUser() {
        val store = newStore()
        store.recordIfBest("u1", "d1", 0.9)

        assertTrue(store.recordIfBest("u2", "d1", 0.3))
        assertEquals(0.9f, saved["best_u1_d1"]!!, 0.0001f)
        assertEquals(0.3f, saved["best_u2_d1"]!!, 0.0001f)
    }

    @Test
    fun store_notBestScore_doesNotWriteToPreferences() {
        val store = newStore()
        store.recordIfBest("u1", "d1", 0.8)

        store.recordIfBest("u1", "d1", 0.1)

        verify(exactly = 1) { editor.apply() }
    }

    @Test
    fun store_usesDedicatedPreferencesFile() {
        newStore()

        verify { context.getSharedPreferences("practice_personal_bests", Context.MODE_PRIVATE) }
    }

    @Test
    fun store_newInstanceSeesBestPersistedByEarlierOne() {
        newStore().recordIfBest("u1", "d1", 0.75)

        assertFalse(newStore().recordIfBest("u1", "d1", 0.70))
        assertTrue(newStore().recordIfBest("u1", "d1", 0.80))
    }
}
