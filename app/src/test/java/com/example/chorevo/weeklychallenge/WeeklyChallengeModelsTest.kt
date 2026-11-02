package com.example.chorevo.weeklychallenge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyChallengeModelsTest {

    private fun challenge(target: Int, progress: Int) = WeeklyChallenge(
        id = "focus_drill",
        type = ChallengeType.FOCUS_DRILL,
        title = "Focus Drill",
        description = "desc",
        emoji = "T",
        targetCount = target,
        progressCount = progress,
        rewardPoints = 40
    )

    @Test
    fun progressFraction_partialProgress_isRatio() {
        assertEquals(0.5f, challenge(target = 4, progress = 2).progressFraction, 0.0001f)
    }

    @Test
    fun progressFraction_noProgress_isZero() {
        assertEquals(0f, challenge(target = 3, progress = 0).progressFraction, 0.0001f)
    }

    @Test
    fun progressFraction_targetReached_isOne() {
        assertEquals(1f, challenge(target = 3, progress = 3).progressFraction, 0.0001f)
    }

    @Test
    fun progressFraction_progressAboveTarget_isCappedAtOne() {
        assertEquals(1f, challenge(target = 2, progress = 5).progressFraction, 0.0001f)
    }

    @Test
    fun progressFraction_negativeProgress_isFlooredAtZero() {
        assertEquals(0f, challenge(target = 4, progress = -2).progressFraction, 0.0001f)
    }

    @Test
    fun progressFraction_zeroTarget_countsAsComplete() {
        assertEquals(1f, challenge(target = 0, progress = 0).progressFraction, 0.0001f)
    }

    @Test
    fun progressFraction_negativeTarget_countsAsComplete() {
        assertEquals(1f, challenge(target = -1, progress = 0).progressFraction, 0.0001f)
    }

    @Test
    fun weeklyChallenge_defaults_areEmptyAndIncomplete() {
        val c = challenge(target = 1, progress = 0).let {
            WeeklyChallenge(
                id = it.id,
                type = it.type,
                title = it.title,
                description = it.description,
                emoji = it.emoji,
                targetCount = 1,
                rewardPoints = 10
            )
        }

        assertEquals(0, c.progressCount)
        assertFalse(c.completed)
        assertNull(c.completedAt)
        assertNull(c.focusMetric)
        assertNull(c.focusThreshold)
        assertTrue(c.requiredStyles.isEmpty())
        assertTrue(c.satisfiedStyles.isEmpty())
    }

    @Test
    fun weeklyChallengeSet_defaults_areEmpty() {
        val set = WeeklyChallengeSet()

        assertEquals("", set.weekStart)
        assertTrue(set.challenges.isEmpty())
        assertFalse(set.allCompleted)
        assertEquals(0, set.bonusRewardPoints)
        assertFalse(set.bonusClaimed)
    }

    @Test
    fun scoreMetric_labelsAreHumanReadable() {
        assertEquals("Alignment", ScoreMetric.ALIGNMENT.label)
        assertEquals("Timing", ScoreMetric.TIMING.label)
        assertEquals("Smoothness", ScoreMetric.SMOOTHNESS.label)
    }

    @Test
    fun scoreMetric_valueOfRoundTripsNames() {
        ScoreMetric.values().forEach { assertEquals(it, ScoreMetric.valueOf(it.name)) }
    }

    @Test
    fun challengeType_hasTheThreeSlots() {
        assertEquals(
            listOf(ChallengeType.FOCUS_DRILL, ChallengeType.STYLE_EXPLORER, ChallengeType.MOMENTUM_GOAL),
            ChallengeType.values().toList()
        )
    }
}
