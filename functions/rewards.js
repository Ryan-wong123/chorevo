// Points, badges, weekly streak and weekly challenges, computed on the server
// from each synced practice session (see rewardsFromSession in index.js).
// The app only reads these; firestore.rules make them read-only for clients.
//
// Everything here is pure, so it is unit tested directly. The formulas match
// what the app used to compute on the phone, so existing totals carry on.

const { danceTypeKey } = require("./discovery");

// ── Points ───────────────────────────────────────────────────────────────────

/** Rewarded sessions per user per day; more than this earns nothing (anti-farming). */
const MAX_REWARDED_SESSIONS_PER_DAY = 30;

function clamp01(value) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : 0;
}

/** alignment × 50 + timing × 30 + smoothness × 20, at most 100 per session. */
function sessionPoints(session) {
  const raw = clamp01(session.alignmentScore) * 50 + clamp01(session.timingConsistencyScore) * 30 +
    clamp01(session.smoothnessScore) * 20;
  return Math.min(100, Math.max(0, Math.floor(raw)));
}

// ── Badges ───────────────────────────────────────────────────────────────────

const BADGE_IDS = [
  "FIRST_STEPS", "PERFECT_POSE", "SMOOTH_OPERATOR", "IN_THE_GROOVE", "HIGH_SCORER",
  "CENTURY", "DEDICATED", "ELITE", "STREAK_STARTER", "WEEK_WARRIOR",
];

function bitCount(mask) {
  let n = 0;
  for (let m = mask; m; m >>= 1) n += m & 1;
  return n;
}

/** Badges newly earned by this session (not already in `earned`). */
function newBadges({ points, totalPointsAfter, session, isFirstSession, weekDaysMask, earned }) {
  const have = new Set(earned || []);
  const out = [];
  const award = (id) => {
    if (!have.has(id) && !out.includes(id)) out.push(id);
  };
  if (isFirstSession) award("FIRST_STEPS");
  if (clamp01(session.alignmentScore) >= 0.95) award("PERFECT_POSE");
  if (clamp01(session.smoothnessScore) >= 0.95) award("SMOOTH_OPERATOR");
  if (clamp01(session.timingConsistencyScore) >= 0.95) award("IN_THE_GROOVE");
  if (points >= 90) award("HIGH_SCORER");
  if (points >= 100) award("CENTURY");
  if (totalPointsAfter >= 500) award("DEDICATED");
  if (totalPointsAfter >= 1000) award("ELITE");
  const days = bitCount(weekDaysMask);
  if (days >= 3) award("STREAK_STARTER");
  if (days >= 7) award("WEEK_WARRIOR");
  return out;
}

// ── Calendar (the user's local day, from the server's clock) ─────────────────

/** Time-zone offsets are limited to real ones (UTC−14:00 … UTC+14:00). */
function safeOffsetMinutes(value) {
  const n = Math.round(Number(value));
  return Number.isFinite(n) ? Math.min(840, Math.max(-840, n)) : 0;
}

function isoDate(ms) {
  return new Date(ms).toISOString().slice(0, 10);
}

/**
 * The user's local date, Monday-start week and weekday (0 = Monday) at
 * `nowMs`. Uses the server's clock and the phone's time-zone offset, so a
 * session can't be backdated to fill in other days.
 */
function localDay(nowMs, tzOffsetMinutes) {
  const local = nowMs + safeOffsetMinutes(tzOffsetMinutes) * 60_000;
  const weekday = (new Date(local).getUTCDay() + 6) % 7; // Monday = 0
  return {
    date: isoDate(local),
    weekStart: isoDate(local - weekday * 86_400_000),
    dayIndex: weekday,
  };
}

/** The weekly streak after practising on `day` (one activity per day). */
function tickStreak(streak, day) {
  const sameWeek = streak && streak.weekStart === day.weekStart;
  const mask = sameWeek ? Number(streak.daysMask) || 0 : 0;
  const total = Number(streak && streak.totalStreakActivities) || 0;
  const already = ((mask >> day.dayIndex) & 1) === 1;
  return {
    weekStart: day.weekStart,
    daysMask: already ? mask : mask | (1 << day.dayIndex),
    totalStreakActivities: already ? total : total + 1,
    lastActivityDate: day.date,
  };
}

// ── Weekly challenges (same rules as the app's former WeeklyChallengeEngine) ─

const FOCUS_ID = "focus_drill";
const STYLE_ID = "style_explorer";
const MOMENTUM_ID = "momentum_goal";
const FOCUS_HISTORY_WINDOW = 10;
const FOCUS_TARGET_COUNT = 2;
const FOCUS_REWARD_POINTS = 40;
const FOCUS_STRETCH_MARGIN = 0.07;
const MIN_FOCUS_THRESHOLD = 0.6;
const MAX_FOCUS_THRESHOLD = 0.95;
const STYLE_DESIRED_COUNT = 2;
const STYLE_REWARD_POINTS = 30;
const MOMENTUM_REWARD_POINTS = 30;
const DEFAULT_MOMENTUM_TARGET = 3;
const MIN_MOMENTUM_TARGET = 2;
const MAX_MOMENTUM_TARGET = 7;
const MOMENTUM_STRETCH = 1;
const BONUS_REWARD_POINTS = 50;
const MOMENTUM_LOOKBACK_WEEKS = 4;

const METRICS = {
  ALIGNMENT: { label: "Alignment", field: "alignmentScore", emoji: "🎯" },
  TIMING: { label: "Timing", field: "timingConsistencyScore", emoji: "🎵" },
  SMOOTHNESS: { label: "Smoothness", field: "smoothnessScore", emoji: "🌊" },
};

const average = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;

function buildFocusDrill(recentScores) {
  const sample = recentScores.slice(0, FOCUS_HISTORY_WINDOW);
  let weakest = "ALIGNMENT";
  let currentAvg = MIN_FOCUS_THRESHOLD - FOCUS_STRETCH_MARGIN;
  if (sample.length) {
    let best = Infinity;
    for (const metric of ["ALIGNMENT", "TIMING", "SMOOTHNESS"]) {
      const avg = average(sample.map((s) => clamp01(s[METRICS[metric].field])));
      if (avg < best) {
        best = avg;
        weakest = metric;
      }
    }
    currentAvg = best;
  }
  const threshold = Math.round(
    Math.min(MAX_FOCUS_THRESHOLD, Math.max(MIN_FOCUS_THRESHOLD, currentAvg + FOCUS_STRETCH_MARGIN)) * 100
  ) / 100;
  const { label, emoji } = METRICS[weakest];
  return {
    id: FOCUS_ID,
    type: "FOCUS_DRILL",
    title: `Focus Drill: ${label}`,
    description: `Score ${Math.floor(threshold * 100)}%+ on ${label} in ${FOCUS_TARGET_COUNT} sessions this week.`,
    emoji,
    targetCount: FOCUS_TARGET_COUNT,
    progressCount: 0,
    rewardPoints: FOCUS_REWARD_POINTS,
    completed: false,
    completedAt: null,
    focusMetric: weakest,
    focusThreshold: threshold,
    requiredStyles: [],
    satisfiedStyles: [],
  };
}

function buildStyleExplorer(availableDanceTypes, random, nowMs) {
  const seen = new Set();
  const styles = [];
  for (const raw of availableDanceTypes) {
    const style = String(raw || "").trim();
    if (!style || seen.has(style.toLowerCase())) continue;
    seen.add(style.toLowerCase());
    styles.push(style);
  }
  // Fisher–Yates shuffle, then take the first few.
  for (let i = styles.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [styles[i], styles[j]] = [styles[j], styles[i]];
  }
  const required = styles.slice(0, Math.min(STYLE_DESIRED_COUNT, styles.length));
  const base = {
    id: STYLE_ID,
    type: "STYLE_EXPLORER",
    title: "Style Explorer",
    emoji: "🧭",
    rewardPoints: STYLE_REWARD_POINTS,
    focusMetric: null,
    focusThreshold: null,
    satisfiedStyles: [],
  };
  if (!required.length) {
    // Not enough styles in the library yet: complete by default.
    return {
      ...base,
      description: "Not enough dance styles in the library yet — this slot is complete by default.",
      targetCount: 0,
      progressCount: 0,
      completed: true,
      completedAt: nowMs,
      requiredStyles: [],
    };
  }
  return {
    ...base,
    description: `Complete a session in each of: ${required.join(", ")}.`,
    targetCount: required.length,
    progressCount: 0,
    completed: false,
    completedAt: null,
    requiredStyles: required,
  };
}

function buildMomentumGoal(recentWeeklySessionCounts) {
  const target = recentWeeklySessionCounts.length
    ? Math.min(MAX_MOMENTUM_TARGET, Math.max(MIN_MOMENTUM_TARGET,
      Math.round(average(recentWeeklySessionCounts)) + MOMENTUM_STRETCH))
    : DEFAULT_MOMENTUM_TARGET;
  return {
    id: MOMENTUM_ID,
    type: "MOMENTUM_GOAL",
    title: "Momentum Goal",
    description: `Complete ${target} practice sessions this week.`,
    emoji: "🔥",
    targetCount: target,
    progressCount: 0,
    rewardPoints: MOMENTUM_REWARD_POINTS,
    completed: false,
    completedAt: null,
    focusMetric: null,
    focusThreshold: null,
    requiredStyles: [],
    satisfiedStyles: [],
  };
}

/**
 * This week's personalised challenge set.
 * @param recentScores the user's recent sessions, most recent first
 * @param availableDanceTypes dance styles live in the feed
 * @param recentWeeklySessionCounts sessions per completed past week, oldest first
 */
function generateChallenges({ weekStart, recentScores, availableDanceTypes, recentWeeklySessionCounts, nowMs, random = Math.random }) {
  const challenges = [
    buildFocusDrill(recentScores || []),
    buildStyleExplorer(availableDanceTypes || [], random, nowMs),
    buildMomentumGoal(recentWeeklySessionCounts || []),
  ];
  return {
    weekStart,
    challenges,
    allCompleted: challenges.every((c) => c.completed),
    bonusRewardPoints: BONUS_REWARD_POINTS,
    bonusClaimed: false,
  };
}

/** Counts sessions per completed week before `currentWeekStart`, oldest first (at most 4). */
function weeklySessionCounts(sessionTimesMs, tzOffsetMinutes, currentWeekStart) {
  const counts = new Map();
  for (const t of sessionTimesMs) {
    const week = localDay(t, tzOffsetMinutes).weekStart;
    if (week === currentWeekStart) continue;
    counts.set(week, (counts.get(week) || 0) + 1);
  }
  return [...counts.keys()].sort().map((k) => counts.get(k)).slice(-MOMENTUM_LOOKBACK_WEEKS);
}

function withProgress(challenge, progressCount, nowMs) {
  const next = { ...challenge, progressCount };
  if (!next.completed && next.targetCount > 0 && next.progressCount >= next.targetCount) {
    next.completed = true;
    next.completedAt = nowMs;
  }
  return next;
}

/**
 * Folds one session into the week's challenges. Returns the new set, the
 * challenges that just completed and whether the all-three bonus unlocked.
 */
function recordChallengeSession(set, session, danceType, nowMs) {
  const newlyCompleted = [];
  const style = String(danceType || "").trim();
  const challenges = set.challenges.map((c) => {
    if (c.completed) return c;
    let updated = c;
    if (c.type === "FOCUS_DRILL") {
      const metric = METRICS[c.focusMetric];
      const score = metric ? clamp01(session[metric.field]) : null;
      if (score != null && typeof c.focusThreshold === "number" && score >= c.focusThreshold) {
        updated = withProgress(c, Math.min(c.progressCount + 1, c.targetCount), nowMs);
      }
    } else if (c.type === "STYLE_EXPLORER") {
      const key = danceTypeKey(style);
      const matches = key && (c.requiredStyles || []).some((s) => danceTypeKey(s) === key);
      const already = (c.satisfiedStyles || []).some((s) => danceTypeKey(s) === key);
      if (matches && !already) {
        const satisfiedStyles = [...(c.satisfiedStyles || []), style];
        updated = withProgress({ ...c, satisfiedStyles }, satisfiedStyles.length, nowMs);
      }
    } else if (c.type === "MOMENTUM_GOAL") {
      updated = withProgress(c, Math.min(c.progressCount + 1, c.targetCount), nowMs);
    }
    if (updated.completed) newlyCompleted.push(updated);
    return updated;
  });
  const allCompleted = challenges.every((c) => c.completed);
  const bonusNewlyUnlocked = allCompleted && !set.bonusClaimed;
  return {
    set: { ...set, challenges, allCompleted, bonusClaimed: set.bonusClaimed || bonusNewlyUnlocked },
    newlyCompleted,
    bonusNewlyUnlocked,
  };
}

/** Stored form (challenges keyed by id, as the app reads it). */
function challengeSetToDoc(set) {
  const challenges = {};
  for (const c of set.challenges) {
    const { id, ...rest } = c;
    challenges[id] = rest;
  }
  return {
    weekStart: set.weekStart,
    allCompleted: set.allCompleted,
    bonusRewardPoints: set.bonusRewardPoints,
    bonusClaimed: set.bonusClaimed,
    challenges,
  };
}

function challengeSetFromDoc(doc) {
  if (!doc || typeof doc.weekStart !== "string") return null;
  const challenges = Object.entries(doc.challenges || {}).map(([id, c]) => ({
    id,
    type: c.type,
    title: c.title || "",
    description: c.description || "",
    emoji: c.emoji || "",
    targetCount: Number(c.targetCount) || 0,
    progressCount: Number(c.progressCount) || 0,
    rewardPoints: Number(c.rewardPoints) || 0,
    completed: c.completed === true,
    completedAt: typeof c.completedAt === "number" ? c.completedAt : null,
    focusMetric: c.focusMetric || null,
    focusThreshold: typeof c.focusThreshold === "number" ? c.focusThreshold : null,
    requiredStyles: Array.isArray(c.requiredStyles) ? c.requiredStyles : [],
    satisfiedStyles: Array.isArray(c.satisfiedStyles) ? c.satisfiedStyles : [],
  }));
  return {
    weekStart: doc.weekStart,
    challenges,
    allCompleted: doc.allCompleted === true,
    bonusRewardPoints: typeof doc.bonusRewardPoints === "number" ? doc.bonusRewardPoints : BONUS_REWARD_POINTS,
    bonusClaimed: doc.bonusClaimed === true,
  };
}

// ── One session, end to end ─────────────────────────────────────────────────

/**
 * Applies one practice session to the user's reward state. Inputs are the
 * stored documents (or null); returns the documents to write and the
 * per-session summary the app shows. Idempotency (one reward per session) is
 * handled by the caller.
 *
 * @param state  points/current: totalPoints, rewardedSessions, alignmentSum, dayKey, rewardedToday
 * @param seed   when state has no rewardedSessions yet: {sessions, alignmentSum} from existing history
 */
function applySession({ session, danceType, nowMs, state, seed, achievements, streak, challengeSet }) {
  const day = localDay(nowMs, session.tzOffsetMinutes);
  const base = state || {};
  const seeded = typeof base.rewardedSessions !== "number";
  const rewardedSessions = seeded ? Number(seed && seed.sessions) || 0 : base.rewardedSessions;
  const alignmentSum = seeded ? Number(seed && seed.alignmentSum) || 0 : Number(base.alignmentSum) || 0;
  const rewardedToday = base.dayKey === day.date ? Number(base.rewardedToday) || 0 : 0;
  const totalBefore = Number(base.totalPoints) || 0;
  const earned = Array.isArray(achievements && achievements.earnedBadges) ? achievements.earnedBadges : [];

  if (rewardedToday >= MAX_REWARDED_SESSIONS_PER_DAY) {
    return {
      capped: true,
      summary: {
        capped: true, sessionPoints: 0, challengePoints: 0, totalPointsBefore: totalBefore,
        totalPointsAfter: totalBefore, newBadges: [], completedChallenges: [], bonusUnlocked: false, bonusPoints: 0,
      },
    };
  }

  const points = sessionPoints(session);
  const newStreak = tickStreak(streak, day);

  let set = challengeSet && challengeSet.weekStart === day.weekStart ? challengeSet : null;
  let completed = [];
  let bonus = false;
  if (set) {
    const r = recordChallengeSession(set, session, danceType, nowMs);
    set = r.set;
    completed = r.newlyCompleted;
    bonus = r.bonusNewlyUnlocked;
  }
  const challengePoints = completed.reduce((a, c) => a + c.rewardPoints, 0) + (bonus ? set.bonusRewardPoints : 0);
  const totalAfter = totalBefore + points + challengePoints;

  const badges = newBadges({
    points,
    totalPointsAfter: totalAfter,
    session,
    isFirstSession: rewardedSessions === 0,
    weekDaysMask: newStreak.daysMask,
    earned,
  });

  return {
    capped: false,
    state: {
      totalPoints: totalAfter,
      lastSessionPoints: points,
      rewardedSessions: rewardedSessions + 1,
      alignmentSum: alignmentSum + clamp01(session.alignmentScore),
      dayKey: day.date,
      rewardedToday: rewardedToday + 1,
    },
    achievements: { earnedBadges: [...earned, ...badges] },
    streak: newStreak,
    challengeSet: set,
    summary: {
      capped: false,
      sessionPoints: points,
      challengePoints,
      totalPointsBefore: totalBefore,
      totalPointsAfter: totalAfter,
      newBadges: badges,
      completedChallenges: completed.map((c) => ({ id: c.id, title: c.title, emoji: c.emoji, rewardPoints: c.rewardPoints })),
      bonusUnlocked: bonus,
      bonusPoints: bonus ? set.bonusRewardPoints : 0,
    },
  };
}

module.exports = {
  MAX_REWARDED_SESSIONS_PER_DAY,
  BADGE_IDS,
  BONUS_REWARD_POINTS,
  sessionPoints,
  newBadges,
  localDay,
  safeOffsetMinutes,
  tickStreak,
  generateChallenges,
  weeklySessionCounts,
  recordChallengeSession,
  challengeSetToDoc,
  challengeSetFromDoc,
  applySession,
};
