// Server-side rewards: points, badges, streak, weekly challenges. The weekly
// challenge cases are ported from the app's former WeeklyChallengeEngineTest.
const test = require("node:test");
const assert = require("node:assert/strict");
const r = require("../rewards");

const session = (a, t = 0.8, s = 0.8, extra = {}) => ({
  alignmentScore: a, timingConsistencyScore: t, smoothnessScore: s, ...extra,
});
const NOW = Date.UTC(2026, 9, 14, 12, 0, 0); // Wednesday 14 Oct 2026, 12:00 UTC
const gen = (opts = {}) => r.generateChallenges({
  weekStart: "2026-10-12", recentScores: [], availableDanceTypes: [], recentWeeklySessionCounts: [],
  nowMs: NOW, random: () => 0, ...opts,
});
const byId = (set, id) => set.challenges.find((c) => c.id === id);

// ── Points and badges ────────────────────────────────────────────────────────

test("session points use the app's formula and are capped at 100", () => {
  assert.equal(r.sessionPoints(session(0.9, 0.8, 0.7)), 83); // 45 + 24 + 14
  assert.equal(r.sessionPoints(session(1, 1, 1)), 100);
  assert.equal(r.sessionPoints(session(50, 50, 50)), 100); // forged scores are clamped
  assert.equal(r.sessionPoints(session(-1, "x", null)), 0);
});

test("badges are awarded once, by the same thresholds as before", () => {
  const badges = r.newBadges({
    points: 100, totalPointsAfter: 1000, session: session(0.96, 0.97, 0.98), isFirstSession: true,
    weekDaysMask: 0b1111111, earned: ["ELITE"],
  });
  assert.deepEqual(badges.sort(), ["CENTURY", "DEDICATED", "FIRST_STEPS", "HIGH_SCORER", "IN_THE_GROOVE",
    "PERFECT_POSE", "SMOOTH_OPERATOR", "STREAK_STARTER", "WEEK_WARRIOR"].sort());
  assert.deepEqual(r.newBadges({ points: 10, totalPointsAfter: 10, session: session(0.1), isFirstSession: false,
    weekDaysMask: 0b11, earned: [] }), []);
});

// ── Calendar and streak ─────────────────────────────────────────────────────

test("localDay uses the server clock in the user's time zone", () => {
  assert.deepEqual(r.localDay(NOW, 0), { date: "2026-10-14", weekStart: "2026-10-12", dayIndex: 2 });
  // 20:00 UTC Sunday is already Monday in Singapore (UTC+8).
  const sundayNight = Date.UTC(2026, 9, 18, 20, 0, 0);
  assert.deepEqual(r.localDay(sundayNight, 480), { date: "2026-10-19", weekStart: "2026-10-19", dayIndex: 0 });
  assert.deepEqual(r.localDay(sundayNight, 0).weekStart, "2026-10-12");
  assert.equal(r.safeOffsetMinutes(99999), 840);
});

test("the streak counts each day once and resets the mask each week", () => {
  const wed = r.localDay(NOW, 0);
  const s1 = r.tickStreak(null, wed);
  assert.deepEqual(s1, { weekStart: "2026-10-12", daysMask: 0b100, totalStreakActivities: 1, lastActivityDate: "2026-10-14" });
  assert.deepEqual(r.tickStreak(s1, wed), s1);
  const nextMon = r.localDay(Date.UTC(2026, 9, 19, 9), 0);
  assert.deepEqual(r.tickStreak(s1, nextMon), { weekStart: "2026-10-19", daysMask: 1, totalStreakActivities: 2, lastActivityDate: "2026-10-19" });
});

// ── Weekly challenge generation (ported) ─────────────────────────────────────

test("no history: focus drill defaults to alignment at the minimum threshold", () => {
  const focus = byId(gen(), "focus_drill");
  assert.equal(focus.focusMetric, "ALIGNMENT");
  assert.equal(focus.focusThreshold, 0.6);
  assert.equal(focus.targetCount, 2);
});

test("focus drill targets the weakest axis, clamped to 0.60..0.95, from the last 10 sessions", () => {
  assert.equal(byId(gen({ recentScores: [session(0.9, 0.5, 0.9)] }), "focus_drill").focusMetric, "TIMING");
  assert.equal(byId(gen({ recentScores: [session(0.99, 0.99, 0.99)] }), "focus_drill").focusThreshold, 0.95);
  assert.equal(byId(gen({ recentScores: [session(0.1, 0.9, 0.9)] }), "focus_drill").focusThreshold, 0.6);
  const recent = Array.from({ length: 10 }, () => session(0.8, 0.8, 0.8));
  const old = Array.from({ length: 10 }, () => session(0.1, 0.8, 0.8));
  assert.equal(byId(gen({ recentScores: [...recent, ...old] }), "focus_drill").focusThreshold, 0.87);
});

test("style explorer picks two distinct styles, one if that's all, or auto-completes", () => {
  const two = byId(gen({ availableDanceTypes: ["Salsa", "salsa", "Hip Hop", "Ballet"] }), "style_explorer");
  assert.equal(two.targetCount, 2);
  assert.equal(new Set(two.requiredStyles.map((s) => s.toLowerCase())).size, 2);
  assert.deepEqual(byId(gen({ availableDanceTypes: ["Salsa"] }), "style_explorer").requiredStyles, ["Salsa"]);
  const none = byId(gen(), "style_explorer");
  assert.equal(none.completed, true);
  assert.equal(none.targetCount, 0);
});

test("momentum goal: 3 by default, recent pace + 1, clamped to 2..7", () => {
  assert.equal(byId(gen(), "momentum_goal").targetCount, 3);
  assert.equal(byId(gen({ recentWeeklySessionCounts: [3, 4, 5] }), "momentum_goal").targetCount, 5);
  assert.equal(byId(gen({ recentWeeklySessionCounts: [20, 30] }), "momentum_goal").targetCount, 7);
  assert.equal(byId(gen({ recentWeeklySessionCounts: [0, 0] }), "momentum_goal").targetCount, 2);
});

test("weeklySessionCounts buckets past weeks, oldest first, excluding this week", () => {
  const day = 86_400_000;
  const times = [NOW, NOW - 7 * day, NOW - 7 * day, NOW - 14 * day];
  assert.deepEqual(r.weeklySessionCounts(times, 0, "2026-10-12"), [1, 2]);
});

// ── Weekly challenge progress (ported) ───────────────────────────────────────

test("focus drill only progresses on qualifying scores and completes at its target", () => {
  let set = gen();
  set = r.recordChallengeSession(set, session(0.5), "", NOW).set;
  assert.equal(byId(set, "focus_drill").progressCount, 0);
  set = r.recordChallengeSession(set, session(0.7), "", NOW).set;
  const res = r.recordChallengeSession(set, session(0.7), "", NOW);
  assert.equal(byId(res.set, "focus_drill").completed, true);
  assert.ok(res.newlyCompleted.some((c) => c.id === "focus_drill"));
  const after = r.recordChallengeSession(res.set, session(0.7), "", NOW);
  assert.equal(byId(after.set, "focus_drill").progressCount, 2);
  assert.ok(!after.newlyCompleted.some((c) => c.id === "focus_drill"));
});

test("style explorer matches styles case-insensitively, once each; other styles don't count", () => {
  let set = gen({ availableDanceTypes: ["Hip Hop", "Salsa"] });
  set = r.recordChallengeSession(set, session(0.5), "hip-hop", NOW).set;
  set = r.recordChallengeSession(set, session(0.5), "HIPHOP", NOW).set;
  assert.equal(byId(set, "style_explorer").progressCount, 1);
  set = r.recordChallengeSession(set, session(0.5), "Ballet", NOW).set;
  assert.equal(byId(set, "style_explorer").progressCount, 1);
  set = r.recordChallengeSession(set, session(0.5), "salsa", NOW).set;
  assert.equal(byId(set, "style_explorer").completed, true);
});

test("the all-three bonus unlocks exactly once", () => {
  let set = gen({ recentWeeklySessionCounts: [0] }); // momentum target 2, style auto-complete
  let bonuses = 0;
  for (let i = 0; i < 4; i++) {
    const res = r.recordChallengeSession(set, session(0.9), "", NOW);
    set = res.set;
    if (res.bonusNewlyUnlocked) bonuses++;
  }
  assert.equal(bonuses, 1);
  assert.equal(set.bonusClaimed, true);
});

test("challenge sets survive the stored format round trip", () => {
  const set = gen({ availableDanceTypes: ["Salsa"] });
  assert.deepEqual(r.challengeSetFromDoc(r.challengeSetToDoc(set)), set);
});

// ── One session end to end ───────────────────────────────────────────────────

test("applySession: points, first badge, streak, challenges and summary", () => {
  const res = r.applySession({
    session: session(0.96, 0.9, 0.9, { tzOffsetMinutes: 0 }), danceType: "", nowMs: NOW,
    state: null, seed: { sessions: 0, alignmentSum: 0 }, achievements: null, streak: null, challengeSet: gen(),
  });
  assert.equal(res.capped, false);
  assert.equal(res.summary.sessionPoints, 93); // 48 + 27 + 18
  assert.ok(res.summary.newBadges.includes("FIRST_STEPS"));
  assert.ok(res.summary.newBadges.includes("PERFECT_POSE"));
  assert.equal(res.state.rewardedSessions, 1);
  assert.equal(res.streak.totalStreakActivities, 1);
  assert.equal(res.summary.totalPointsAfter, res.summary.totalPointsBefore + 93 + res.summary.challengePoints);
  assert.equal(res.state.totalPoints, res.summary.totalPointsAfter);
});

test("applySession continues existing totals and history", () => {
  const res = r.applySession({
    session: session(0.5, 0.5, 0.5), danceType: "", nowMs: NOW,
    state: { totalPoints: 480 }, seed: { sessions: 12, alignmentSum: 9 },
    achievements: { earnedBadges: ["FIRST_STEPS"] }, streak: null, challengeSet: null,
  });
  assert.equal(res.state.totalPoints, 530);
  assert.equal(res.state.rewardedSessions, 13);
  assert.ok(res.summary.newBadges.includes("DEDICATED"));
  assert.ok(!res.summary.newBadges.includes("FIRST_STEPS"));
});

test("applySession stops rewarding after the daily cap", () => {
  const today = r.localDay(NOW, 0).date;
  const res = r.applySession({
    session: session(1, 1, 1), danceType: "", nowMs: NOW,
    state: { totalPoints: 100, rewardedSessions: 40, alignmentSum: 30, dayKey: today, rewardedToday: r.MAX_REWARDED_SESSIONS_PER_DAY },
    achievements: null, streak: null, challengeSet: null,
  });
  assert.equal(res.capped, true);
  assert.equal(res.summary.sessionPoints, 0);
  assert.equal(res.summary.totalPointsAfter, 100);
});
