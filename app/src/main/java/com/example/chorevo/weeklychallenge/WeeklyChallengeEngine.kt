package com.example.chorevo.weeklychallenge

import com.example.chorevo.dancelibrarylist.danceTypeKey
import kotlin.random.Random
import kotlin.math.roundToInt

/**
 * Generates and updates this week's [WeeklyChallengeSet].
 *
 * This is what makes the feature different from a generic "do 3 workouts this week"
 * quest list: every slot is derived from data Chorevo already uniquely has —
 *
 * - Focus Drill reads the learner's own recent AI pose-similarity scores
 *   (alignment / timing / smoothness) and targets whichever axis is currently
 *   their weakest, at a threshold just above their own recent average.
 * - Style Explorer reads the dance types actually live in the creator-uploaded
 *   library and asks the learner to branch out into a couple of them.
 * - Momentum Goal scales its session-count target to the learner's own recent
 *   weekly pace instead of a flat number that is too easy for some and too
 *   hard for others.
 *
 * No Firebase, Android, or coroutine dependencies live here on purpose — every
 * function is a pure transformation so the reward logic can be unit tested
 * directly, the same way [com.example.chorevo.danceactivity.evaluateNewBadges]
 * and the pose-similarity scoring math are tested.
 */
object WeeklyChallengeEngine {

    const val FOCUS_ID = "focus_drill"
    const val STYLE_ID = "style_explorer"
    const val MOMENTUM_ID = "momentum_goal"

    private const val FOCUS_HISTORY_WINDOW = 10
    private const val FOCUS_TARGET_COUNT = 2
    private const val FOCUS_REWARD_POINTS = 40
    private const val FOCUS_STRETCH_MARGIN = 0.07
    private const val MIN_FOCUS_THRESHOLD = 0.60
    private const val MAX_FOCUS_THRESHOLD = 0.95

    private const val STYLE_DESIRED_COUNT = 2
    private const val STYLE_REWARD_POINTS = 30

    private const val MOMENTUM_REWARD_POINTS = 30
    private const val DEFAULT_MOMENTUM_TARGET = 3
    private const val MIN_MOMENTUM_TARGET = 2
    private const val MAX_MOMENTUM_TARGET = 7
    private const val MOMENTUM_STRETCH = 1

    const val BONUS_REWARD_POINTS = 50

    /**
     * Builds a brand-new challenge set for [weekStart]. Only called when no set
     * exists yet for the current week — an existing in-progress set is never
     * regenerated mid-week, so progress is never lost.
     *
     * @param recentScores the learner's most recent completed sessions, most
     *   recent first. Used to find their weakest score axis and its current average.
     * @param availableDanceTypes distinct, practice-ready dance types currently in
     *   the library. Used so Style Explorer never asks for content that does not exist.
     * @param recentWeeklySessionCounts how many sessions the learner completed in
     *   each of the last few calendar weeks, oldest first. Used to scale the volume
     *   goal to their own pace rather than a flat number for every learner.
     * @param random source of randomness for picking which styles to require;
     *   exposed for deterministic tests.
     */
    fun generate(
        weekStart: String,
        recentScores: List<RecentSessionScore>,
        availableDanceTypes: List<String>,
        recentWeeklySessionCounts: List<Int>,
        random: Random = Random.Default
    ): WeeklyChallengeSet {
        val challenges = listOf(
            buildFocusDrill(recentScores),
            buildStyleExplorer(availableDanceTypes, random),
            buildMomentumGoal(recentWeeklySessionCounts)
        )

        return WeeklyChallengeSet(
            weekStart = weekStart,
            challenges = challenges,
            allCompleted = challenges.all { it.completed },
            bonusRewardPoints = BONUS_REWARD_POINTS,
            bonusClaimed = false
        )
    }

    /**
     * Folds one freshly completed practice session into [set], advancing whichever
     * challenge slots that session qualifies for. Pure: returns a new set rather
     * than mutating [set], and reports which slots (if any) just flipped to completed
     * so the caller can award points and show a celebration.
     */
    fun recordSession(
        set: WeeklyChallengeSet,
        danceType: String,
        alignmentScore: Double,
        timingConsistencyScore: Double,
        smoothnessScore: Double
    ): WeeklyChallengeUpdateResult {
        val normalizedStyle = danceType.trim()
        val newlyCompleted = mutableListOf<WeeklyChallenge>()

        val updatedChallenges = set.challenges.map { challenge ->
            if (challenge.completed) return@map challenge

            val updated = when (challenge.type) {
                ChallengeType.FOCUS_DRILL -> applyFocusDrill(
                    challenge = challenge,
                    alignmentScore = alignmentScore,
                    timingConsistencyScore = timingConsistencyScore,
                    smoothnessScore = smoothnessScore
                )

                ChallengeType.STYLE_EXPLORER -> applyStyleExplorer(
                    challenge = challenge,
                    sessionStyle = normalizedStyle
                )

                ChallengeType.MOMENTUM_GOAL -> challenge.bumpProgress()
            }

            if (updated.completed) newlyCompleted += updated
            updated
        }

        val allCompleted = updatedChallenges.all { it.completed }
        val bonusNewlyUnlocked = allCompleted && !set.bonusClaimed

        val updatedSet = set.copy(
            challenges = updatedChallenges,
            allCompleted = allCompleted,
            bonusClaimed = set.bonusClaimed || bonusNewlyUnlocked
        )

        return WeeklyChallengeUpdateResult(
            challengeSet = updatedSet,
            newlyCompleted = newlyCompleted,
            bonusNewlyUnlocked = bonusNewlyUnlocked
        )
    }

    // ── Generation helpers ──────────────────────────────────────────────────

    private fun buildFocusDrill(recentScores: List<RecentSessionScore>): WeeklyChallenge {
        val sample = recentScores.take(FOCUS_HISTORY_WINDOW)

        val weakest: ScoreMetric
        val currentAvg: Double

        if (sample.isEmpty()) {
            // No history yet: start with an easy, encouraging first target.
            weakest = ScoreMetric.ALIGNMENT
            currentAvg = MIN_FOCUS_THRESHOLD - FOCUS_STRETCH_MARGIN
        } else {
            val averages = mapOf(
                ScoreMetric.ALIGNMENT to sample.map { it.alignmentScore }.average(),
                ScoreMetric.TIMING to sample.map { it.timingConsistencyScore }.average(),
                ScoreMetric.SMOOTHNESS to sample.map { it.smoothnessScore }.average()
            )
            val weakestEntry = averages.entries.minByOrNull { it.value }!!
            weakest = weakestEntry.key
            currentAvg = weakestEntry.value
        }

        val threshold = ((currentAvg + FOCUS_STRETCH_MARGIN)
            .coerceIn(MIN_FOCUS_THRESHOLD, MAX_FOCUS_THRESHOLD) * 100.0)
            .roundToInt() / 100.0
        val percentLabel = (threshold * 100).toInt()

        return WeeklyChallenge(
            id = FOCUS_ID,
            type = ChallengeType.FOCUS_DRILL,
            title = "Focus Drill: ${weakest.label}",
            description = "Score $percentLabel%+ on ${weakest.label} in $FOCUS_TARGET_COUNT sessions this week.",
            emoji = weakest.focusEmoji(),
            targetCount = FOCUS_TARGET_COUNT,
            rewardPoints = FOCUS_REWARD_POINTS,
            focusMetric = weakest,
            focusThreshold = threshold
        )
    }

    private fun buildStyleExplorer(availableDanceTypes: List<String>, random: Random): WeeklyChallenge {
        val distinctStyles = availableDanceTypes
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        val pickCount = minOf(STYLE_DESIRED_COUNT, distinctStyles.size)
        val required = if (pickCount <= 0) emptyList() else distinctStyles.shuffled(random).take(pickCount)

        if (required.isEmpty()) {
            // Library does not have enough distinct styles yet — auto-complete this
            // slot rather than asking for content that does not exist.
            return WeeklyChallenge(
                id = STYLE_ID,
                type = ChallengeType.STYLE_EXPLORER,
                title = "Style Explorer",
                description = "Not enough dance styles in the library yet — this slot is complete by default.",
                emoji = "🧭",
                targetCount = 0,
                progressCount = 0,
                rewardPoints = STYLE_REWARD_POINTS,
                completed = true,
                completedAt = System.currentTimeMillis()
            )
        }

        return WeeklyChallenge(
            id = STYLE_ID,
            type = ChallengeType.STYLE_EXPLORER,
            title = "Style Explorer",
            description = "Complete a session in each of: ${required.joinToString(", ")}.",
            emoji = "🧭",
            targetCount = required.size,
            rewardPoints = STYLE_REWARD_POINTS,
            requiredStyles = required
        )
    }

    private fun buildMomentumGoal(recentWeeklySessionCounts: List<Int>): WeeklyChallenge {
        val target = if (recentWeeklySessionCounts.isEmpty()) {
            DEFAULT_MOMENTUM_TARGET
        } else {
            val avg = recentWeeklySessionCounts.average()
            (Math.round(avg).toInt() + MOMENTUM_STRETCH)
                .coerceIn(MIN_MOMENTUM_TARGET, MAX_MOMENTUM_TARGET)
        }

        return WeeklyChallenge(
            id = MOMENTUM_ID,
            type = ChallengeType.MOMENTUM_GOAL,
            title = "Momentum Goal",
            description = "Complete $target practice sessions this week.",
            emoji = "🔥",
            targetCount = target,
            rewardPoints = MOMENTUM_REWARD_POINTS
        )
    }

    // ── Update helpers ───────────────────────────────────────────────────────

    private fun applyFocusDrill(
        challenge: WeeklyChallenge,
        alignmentScore: Double,
        timingConsistencyScore: Double,
        smoothnessScore: Double
    ): WeeklyChallenge {
        val score = when (challenge.focusMetric) {
            ScoreMetric.ALIGNMENT -> alignmentScore
            ScoreMetric.TIMING -> timingConsistencyScore
            ScoreMetric.SMOOTHNESS -> smoothnessScore
            null -> null
        }
        val threshold = challenge.focusThreshold

        return if (score != null && threshold != null && score >= threshold) {
            challenge.bumpProgress()
        } else {
            challenge
        }
    }

    private fun applyStyleExplorer(challenge: WeeklyChallenge, sessionStyle: String): WeeklyChallenge {
        if (sessionStyle.isBlank()) return challenge

        // Compare style keys so "Hip Hop", "hip-hop" and "HIPHOP" count as the same style.
        val sessionKey = danceTypeKey(sessionStyle)
        val matches = challenge.requiredStyles.any { danceTypeKey(it) == sessionKey }
        val alreadySatisfied = challenge.satisfiedStyles.any { danceTypeKey(it) == sessionKey }

        if (!matches || alreadySatisfied) return challenge

        val updatedStyles = challenge.satisfiedStyles + sessionStyle
        return challenge.copy(
            satisfiedStyles = updatedStyles,
            progressCount = updatedStyles.size
        ).maybeComplete()
    }

    private fun WeeklyChallenge.bumpProgress(): WeeklyChallenge {
        val next = (progressCount + 1).coerceAtMost(targetCount)
        return copy(progressCount = next).maybeComplete()
    }

    private fun WeeklyChallenge.maybeComplete(): WeeklyChallenge {
        return if (!completed && targetCount > 0 && progressCount >= targetCount) {
            copy(completed = true, completedAt = System.currentTimeMillis())
        } else {
            this
        }
    }

    private fun ScoreMetric.focusEmoji(): String = when (this) {
        ScoreMetric.ALIGNMENT -> "🎯"
        ScoreMetric.TIMING -> "🎵"
        ScoreMetric.SMOOTHNESS -> "🌊"
    }
}
