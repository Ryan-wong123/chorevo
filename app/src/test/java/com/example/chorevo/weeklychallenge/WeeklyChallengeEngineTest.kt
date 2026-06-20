package com.example.chorevo.weeklychallenge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WeeklyChallengeEngineTest {

    private fun score(alignment: Double, timing: Double = 0.8, smoothness: Double = 0.8) =
        RecentSessionScore(
            alignmentScore = alignment,
            timingConsistencyScore = timing,
            smoothnessScore = smoothness
        )

    private fun WeeklyChallengeSet.challenge(id: String) = challenges.first { it.id == id }

    // ── generate(): Focus Drill ─────────────────────────────────────────────

    @Test
    fun generate_noHistory_focusDrillDefaultsToAlignmentAtMinThreshold() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList()
        )

        val focus = set.challenge(WeeklyChallengeEngine.FOCUS_ID)
        assertEquals(ScoreMetric.ALIGNMENT, focus.focusMetric)
        assertEquals(0.60, focus.focusThreshold!!, 1e-6)
    }

    @Test
    fun generate_picksWeakestAxis_asFocusMetric() {
        val history = List(5) { score(alignment = 0.95, timing = 0.95, smoothness = 0.50) }
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = history,
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList()
        )

        val focus = set.challenge(WeeklyChallengeEngine.FOCUS_ID)
        assertEquals(ScoreMetric.SMOOTHNESS, focus.focusMetric)
        assertEquals(0.60, focus.focusThreshold!!, 1e-6) // 0.50 average + 0.07 stretch, clamped to minimum
    }

    @Test
    fun generate_focusThreshold_clampsToMaximum_whenRecentAverageIsVeryHigh() {
        val history = List(10) { score(alignment = 0.99, timing = 0.99, smoothness = 0.99) }
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = history,
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList()
        )

        assertEquals(0.95, set.challenge(WeeklyChallengeEngine.FOCUS_ID).focusThreshold!!, 1e-6)
    }

    @Test
    fun generate_focusThreshold_clampsToMinimum_whenRecentAverageIsVeryLow() {
        val history = List(10) { score(alignment = 0.10, timing = 0.90, smoothness = 0.90) }
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = history,
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList()
        )

        // Even a very weak average never produces a threshold below MIN_FOCUS_THRESHOLD.
        assertEquals(0.60, set.challenge(WeeklyChallengeEngine.FOCUS_ID).focusThreshold!!, 1e-6)
    }

    @Test
    fun generate_focusDrill_onlyUsesMostRecentTenSessions() {
        // Most-recent-first input: 10 recent sessions with weak alignment, followed by
        // 20 older sessions with catastrophic timing. If the window were not respected,
        // the combined average would make TIMING the weakest axis instead of ALIGNMENT.
        val recentSessions = List(10) { score(alignment = 0.60, timing = 0.95, smoothness = 0.95) }
        val olderSessions = List(20) { score(alignment = 0.95, timing = 0.05, smoothness = 0.95) }

        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = recentSessions + olderSessions,
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList()
        )

        val focus = set.challenge(WeeklyChallengeEngine.FOCUS_ID)
        assertEquals(ScoreMetric.ALIGNMENT, focus.focusMetric)
        assertEquals(0.67, focus.focusThreshold!!, 1e-6)
    }

    // ── generate(): Style Explorer ───────────────────────────────────────────

    @Test
    fun generate_styleExplorer_picksTwoDistinctStyles_dedupingByCase() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = listOf("Salsa", "salsa", "Bachata", "Tango"),
            recentWeeklySessionCounts = emptyList(),
            random = Random(42)
        )

        val style = set.challenge(WeeklyChallengeEngine.STYLE_ID)
        assertEquals(2, style.requiredStyles.size)
        assertEquals(2, style.targetCount)
        assertFalse(style.completed)

        val distinctPool = setOf("Salsa", "Bachata", "Tango")
        assertTrue(style.requiredStyles.all { it in distinctPool })
    }

    @Test
    fun generate_styleExplorer_withOnlyOneStyleAvailable_targetsJustThatOne() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = listOf("Salsa"),
            recentWeeklySessionCounts = emptyList()
        )

        val style = set.challenge(WeeklyChallengeEngine.STYLE_ID)
        assertEquals(listOf("Salsa"), style.requiredStyles)
        assertEquals(1, style.targetCount)
        assertFalse(style.completed)
    }

    @Test
    fun generate_styleExplorer_withNoStylesAvailable_autoCompletes() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList()
        )

        val style = set.challenge(WeeklyChallengeEngine.STYLE_ID)
        assertTrue(style.completed)
        assertEquals(0, style.targetCount)
    }

    // ── generate(): Momentum Goal ────────────────────────────────────────────

    @Test
    fun generate_momentumGoal_defaultsToThree_whenNoHistory() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList()
        )

        assertEquals(3, set.challenge(WeeklyChallengeEngine.MOMENTUM_ID).targetCount)
    }

    @Test
    fun generate_momentumGoal_scalesToRecentPace_withStretch() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = listOf(4, 5, 6) // average 5, +1 stretch = 6
        )

        assertEquals(6, set.challenge(WeeklyChallengeEngine.MOMENTUM_ID).targetCount)
    }

    @Test
    fun generate_momentumGoal_clampsToMaximumOfSeven() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = listOf(10, 12, 14)
        )

        assertEquals(7, set.challenge(WeeklyChallengeEngine.MOMENTUM_ID).targetCount)
    }

    @Test
    fun generate_momentumGoal_clampsToMinimumOfTwo() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = listOf(0, 0, 1)
        )

        assertEquals(2, set.challenge(WeeklyChallengeEngine.MOMENTUM_ID).targetCount)
    }

    // ── recordSession(): Focus Drill ─────────────────────────────────────────

    @Test
    fun recordSession_focusDrill_progressesOnlyWhenScoreMeetsThreshold() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(), // threshold defaults to 0.60
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList()
        )
        val threshold = set.challenge(WeeklyChallengeEngine.FOCUS_ID).focusThreshold!!

        val belowResult = WeeklyChallengeEngine.recordSession(
            set = set,
            danceType = "Salsa",
            alignmentScore = threshold - 0.05,
            timingConsistencyScore = 0.9,
            smoothnessScore = 0.9
        )
        assertEquals(0, belowResult.challengeSet.challenge(WeeklyChallengeEngine.FOCUS_ID).progressCount)
        assertTrue(belowResult.newlyCompleted.isEmpty())

        val aboveResult = WeeklyChallengeEngine.recordSession(
            set = belowResult.challengeSet,
            danceType = "Salsa",
            alignmentScore = threshold,
            timingConsistencyScore = 0.9,
            smoothnessScore = 0.9
        )
        val focusAfterAbove = aboveResult.challengeSet.challenge(WeeklyChallengeEngine.FOCUS_ID)
        assertEquals(1, focusAfterAbove.progressCount)
        assertFalse(focusAfterAbove.completed)
    }

    @Test
    fun recordSession_focusDrill_completesAtTarget_andIgnoresExtraSessionsAfter() {
        var set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList()
        )
        val threshold = set.challenge(WeeklyChallengeEngine.FOCUS_ID).focusThreshold!!

        repeat(2) {
            val result = WeeklyChallengeEngine.recordSession(
                set = set,
                danceType = "Salsa",
                alignmentScore = threshold,
                timingConsistencyScore = 0.9,
                smoothnessScore = 0.9
            )
            set = result.challengeSet
        }

        val focusAfterTwo = set.challenge(WeeklyChallengeEngine.FOCUS_ID)
        assertTrue(focusAfterTwo.completed)
        assertEquals(2, focusAfterTwo.progressCount)

        val thirdResult = WeeklyChallengeEngine.recordSession(
            set = set,
            danceType = "Salsa",
            alignmentScore = threshold,
            timingConsistencyScore = 0.9,
            smoothnessScore = 0.9
        )
        val focusAfterThree = thirdResult.challengeSet.challenge(WeeklyChallengeEngine.FOCUS_ID)
        assertEquals(2, focusAfterThree.progressCount)
        assertFalse(thirdResult.newlyCompleted.any { it.id == WeeklyChallengeEngine.FOCUS_ID })
    }

    // ── recordSession(): Style Explorer ──────────────────────────────────────

    @Test
    fun recordSession_styleExplorer_matchesCaseInsensitively_andDedupesRepeats() {
        var set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = listOf("Salsa", "Bachata"),
            recentWeeklySessionCounts = emptyList(),
            random = Random(1)
        )
        val required = set.challenge(WeeklyChallengeEngine.STYLE_ID).requiredStyles

        val result1 = WeeklyChallengeEngine.recordSession(
            set = set,
            danceType = required[0].uppercase(),
            alignmentScore = 0.9,
            timingConsistencyScore = 0.9,
            smoothnessScore = 0.9
        )
        set = result1.challengeSet
        assertEquals(1, set.challenge(WeeklyChallengeEngine.STYLE_ID).progressCount)

        // Repeating the same style must not double-count it.
        val result2 = WeeklyChallengeEngine.recordSession(
            set = set,
            danceType = required[0],
            alignmentScore = 0.9,
            timingConsistencyScore = 0.9,
            smoothnessScore = 0.9
        )
        set = result2.challengeSet
        assertEquals(1, set.challenge(WeeklyChallengeEngine.STYLE_ID).progressCount)

        val result3 = WeeklyChallengeEngine.recordSession(
            set = set,
            danceType = required[1],
            alignmentScore = 0.9,
            timingConsistencyScore = 0.9,
            smoothnessScore = 0.9
        )
        val styleAfter = result3.challengeSet.challenge(WeeklyChallengeEngine.STYLE_ID)
        assertTrue(styleAfter.completed)
        assertTrue(result3.newlyCompleted.any { it.id == WeeklyChallengeEngine.STYLE_ID })
    }

    @Test
    fun recordSession_styleExplorer_unrelatedStyleDoesNotProgress() {
        val set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = listOf("Salsa", "Bachata"),
            recentWeeklySessionCounts = emptyList(),
            random = Random(1)
        )

        val result = WeeklyChallengeEngine.recordSession(
            set = set,
            danceType = "Hip Hop",
            alignmentScore = 0.9,
            timingConsistencyScore = 0.9,
            smoothnessScore = 0.9
        )

        assertEquals(0, result.challengeSet.challenge(WeeklyChallengeEngine.STYLE_ID).progressCount)
    }

    // ── recordSession(): Momentum Goal ──────────────────────────────────────

    @Test
    fun recordSession_momentumGoal_incrementsOnEverySession_regardlessOfScoreOrStyle() {
        var set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(),
            availableDanceTypes = emptyList(),
            recentWeeklySessionCounts = emptyList() // target defaults to 3
        )

        repeat(3) {
            val result = WeeklyChallengeEngine.recordSession(
                set = set,
                danceType = "AnythingGoes",
                alignmentScore = 0.0,
                timingConsistencyScore = 0.0,
                smoothnessScore = 0.0
            )
            set = result.challengeSet
        }

        val momentum = set.challenge(WeeklyChallengeEngine.MOMENTUM_ID)
        assertTrue(momentum.completed)
        assertEquals(3, momentum.progressCount)
    }

    // ── recordSession(): all-completed bonus ─────────────────────────────────

    @Test
    fun recordSession_bonusUnlocksExactlyOnce_whenAllThreeChallengesComplete() {
        var set = WeeklyChallengeEngine.generate(
            weekStart = "2026-06-15",
            recentScores = emptyList(), // focus threshold defaults to 0.60
            availableDanceTypes = listOf("Salsa"), // style target = 1
            recentWeeklySessionCounts = emptyList() // momentum target defaults to 3
        )

        // Session 1: satisfies Focus Drill progress (1/2) and completes Style Explorer (1/1).
        var result = WeeklyChallengeEngine.recordSession(set, "Salsa", 0.60, 0.9, 0.9)
        set = result.challengeSet
        assertTrue(result.newlyCompleted.any { it.id == WeeklyChallengeEngine.STYLE_ID })
        assertFalse(result.bonusNewlyUnlocked)

        // Session 2: completes Focus Drill (2/2).
        result = WeeklyChallengeEngine.recordSession(set, "Salsa", 0.60, 0.9, 0.9)
        set = result.challengeSet
        assertTrue(result.newlyCompleted.any { it.id == WeeklyChallengeEngine.FOCUS_ID })
        assertFalse(result.bonusNewlyUnlocked)

        // Session 3: completes Momentum Goal (3/3) -> the all-three bonus unlocks.
        result = WeeklyChallengeEngine.recordSession(set, "AnythingElse", 0.0, 0.0, 0.0)
        set = result.challengeSet
        assertTrue(result.newlyCompleted.any { it.id == WeeklyChallengeEngine.MOMENTUM_ID })
        assertTrue(result.bonusNewlyUnlocked)
        assertTrue(set.allCompleted)
        assertTrue(set.bonusClaimed)

        // A further session must not re-fire the bonus or report any new completions.
        val extraResult = WeeklyChallengeEngine.recordSession(set, "AnythingElse", 0.0, 0.0, 0.0)
        assertFalse(extraResult.bonusNewlyUnlocked)
        assertTrue(extraResult.newlyCompleted.isEmpty())
    }
}
