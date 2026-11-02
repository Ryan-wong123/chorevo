package com.example.chorevo.danceactivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementModelsTest {

    // ── levelForPoints ────────────────────────────────────────────────────────

    @Test
    fun levelForPoints_zero_isBeginner() {
        assertEquals("Beginner", levelForPoints(0).title)
        assertEquals(1, levelForPoints(0).level)
    }

    @Test
    fun levelForPoints_negative_fallsBackToFirstLevel() {
        assertEquals(1, levelForPoints(-50).level)
    }

    @Test
    fun levelForPoints_justBelowThreshold_staysOnLowerLevel() {
        assertEquals(1, levelForPoints(199).level)
        assertEquals(2, levelForPoints(499).level)
        assertEquals(3, levelForPoints(999).level)
        assertEquals(4, levelForPoints(1999).level)
    }

    @Test
    fun levelForPoints_exactlyAtThreshold_reachesThatLevel() {
        assertEquals(2, levelForPoints(200).level)
        assertEquals(3, levelForPoints(500).level)
        assertEquals(4, levelForPoints(1000).level)
        assertEquals(5, levelForPoints(2000).level)
    }

    @Test
    fun levelForPoints_aboveTopThreshold_staysOnTopLevel() {
        val top = levelForPoints(1_000_000)
        assertEquals(5, top.level)
        assertEquals("Choreographer", top.title)
    }

    // ── nextLevelForPoints ────────────────────────────────────────────────────

    @Test
    fun nextLevelForPoints_beginner_isMover() {
        val next = nextLevelForPoints(0)
        assertNotNull(next)
        assertEquals("Mover", next!!.title)
        assertEquals(200, next.pointsRequired)
    }

    @Test
    fun nextLevelForPoints_midLevel_isFollowingLevel() {
        assertEquals(4, nextLevelForPoints(500)!!.level)
        assertEquals(5, nextLevelForPoints(1500)!!.level)
    }

    @Test
    fun nextLevelForPoints_atTopLevel_isNull() {
        assertNull(nextLevelForPoints(2000))
        assertNull(nextLevelForPoints(99_999))
    }

    // ── level table ───────────────────────────────────────────────────────────

    @Test
    fun achievementLevels_areNumberedConsecutivelyAndThresholdsIncrease() {
        assertEquals((1..ACHIEVEMENT_LEVELS.size).toList(), ACHIEVEMENT_LEVELS.map { it.level })
        assertEquals(0, ACHIEVEMENT_LEVELS.first().pointsRequired)
        val thresholds = ACHIEVEMENT_LEVELS.map { it.pointsRequired }
        assertEquals(thresholds.sorted(), thresholds)
        assertEquals(thresholds.distinct(), thresholds)
    }

    // ── badges ────────────────────────────────────────────────────────────────

    @Test
    fun allBadges_coverEveryBadgeIdExactlyOnce() {
        assertEquals(BadgeId.values().size, ALL_BADGES.size)
        assertEquals(BadgeId.values().toSet(), ALL_BADGES.map { it.id }.toSet())
    }

    @Test
    fun allBadges_haveUniqueNonBlankNamesAndDescriptions() {
        assertEquals(ALL_BADGES.size, ALL_BADGES.map { it.name }.toSet().size)
        assertTrue(ALL_BADGES.all { it.name.isNotBlank() })
        assertTrue(ALL_BADGES.all { it.description.isNotBlank() })
        assertTrue(ALL_BADGES.all { it.emoji.isNotBlank() })
    }

    @Test
    fun allBadges_describeServerThresholds() {
        assertTrue(ALL_BADGES.first { it.id == BadgeId.DEDICATED }.description.contains("500"))
        assertTrue(ALL_BADGES.first { it.id == BadgeId.ELITE }.description.contains("1000"))
        assertTrue(ALL_BADGES.first { it.id == BadgeId.CENTURY }.description.contains("100"))
    }
}
