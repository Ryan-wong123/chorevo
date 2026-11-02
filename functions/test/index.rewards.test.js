// rewardsFromSession, weeklyChallenges, leaderboardFromProfile.
const test = require("node:test");
const assert = require("node:assert/strict");
const h = require("./support/harness");
const rewards = require("../rewards");

const idx = h.loadIndex();
const NOW = Date.UTC(2026, 9, 14, 12); // Wednesday 14 Oct 2026, 12:00 UTC
const WEEK = "2026-10-12";
const rejectsWith = (p, code) => assert.rejects(p, (e) => e instanceof h.HttpsError && e.code === code);

const session = (extra = {}) => ({
  alignmentScore: 0.9, timingConsistencyScore: 0.8, smoothnessScore: 0.7, tsStart: NOW, tzOffsetMinutes: 0,
  analysis: { danceId: "d1" }, ...extra,
});
const sessionEvent = (c, s, id = "s1", uid = "u1") =>
  h.docEvent(c.db, `users/${uid}/danceLearningSessions/${id}`, s, { uid, sessionId: id });
const baseDocs = (s = session(), extra = {}) => ({
  "users/u1": { displayName: "Ann", photoUrl: "https://img.example/a.png" },
  "danceLibrary/d1": { danceType: "Salsa" },
  "users/u1/danceLearningSessions/s1": s,
  ...extra,
});

// ── rewardsFromSession ───────────────────────────────────────────────────────

test("rewardsFromSession rewards a first session: points, badge, streak, challenges and leaderboard in one go", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: baseDocs() });
  await idx.rewardsFromSession(sessionEvent(c, session()));
  const reward = c.db.data("users/u1/sessionRewards/s1");
  assert.equal(reward.sessionId, "s1");
  assert.equal(reward.sessionPoints, 83); // 45 + 24 + 14
  assert.equal(reward.totalPointsAfter, 83);
  assert.equal(reward.createdAt, NOW);
  assert.ok(reward.newBadges.includes("FIRST_STEPS"));

  const state = c.db.data("users/u1/points/current");
  assert.equal(state.totalPoints, 83);
  assert.equal(state.rewardedSessions, 1);
  assert.equal(state.alignmentSum, 0.9);
  assert.equal(state.dayKey, "2026-10-14");
  assert.deepEqual(c.db.data("users/u1/achievements/current").earnedBadges, reward.newBadges);

  const streak = c.db.data("users/u1/streaks/current");
  assert.equal(streak.weekStart, WEEK);
  assert.equal(streak.totalStreakActivities, 1);

  const week = c.db.data(`users/u1/weeklyChallenges/${WEEK}`);
  assert.equal(week.weekStart, WEEK);
  assert.ok(week.challenges.momentum_goal.progressCount >= 1);

  const board = c.db.data("leaderboardStats/u1");
  assert.equal(board.displayName, "Ann");
  assert.equal(board.totalPracticeSessions, 1);
  assert.equal(board.avgSimilarityScore, 0.9);
  assert.equal(board.totalActiveDays, 1);
});

test("rewardsFromSession rewards each session exactly once (replayed events change nothing)", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: baseDocs() });
  await idx.rewardsFromSession(sessionEvent(c, session()));
  const snapshot = new Map(c.db.docs);
  c.db.log.length = 0;
  await idx.rewardsFromSession(sessionEvent(c, session()));
  assert.deepEqual(c.db.log, []);
  assert.deepEqual(c.db.docs, snapshot);
});

test("rewardsFromSession accumulates onto existing reward state without re-counting history", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: baseDocs(session(), {
    "users/u1/points/current": { totalPoints: 500, rewardedSessions: 4, alignmentSum: 3, dayKey: "2026-10-13", rewardedToday: 9 },
    "users/u1/streaks/current": { weekStart: WEEK, daysMask: 0b1, totalStreakActivities: 10, lastActivityDate: "2026-10-12" },
    "users/u1/achievements/current": { earnedBadges: ["FIRST_STEPS"] },
    "users/u1/danceLearningSessions/old": { alignmentScore: 0.1, tsStart: NOW - 1000 },
  }) });
  await idx.rewardsFromSession(sessionEvent(c, session()));
  const state = c.db.data("users/u1/points/current");
  assert.equal(state.totalPoints, 583);
  assert.equal(state.rewardedSessions, 5);
  assert.equal(state.rewardedToday, 1); // new day resets the daily counter
  assert.ok(Math.abs(state.alignmentSum - 3.9) < 1e-9);
  assert.equal(c.db.data("users/u1/streaks/current").totalStreakActivities, 11);
  assert.equal(c.db.data("users/u1/streaks/current").daysMask, 0b101); // Monday + Wednesday
  assert.ok(c.db.data("users/u1/achievements/current").earnedBadges.includes("FIRST_STEPS"));
  assert.equal(c.db.data("leaderboardStats/u1").totalPracticeSessions, 5);
});

test("rewardsFromSession counts a user's earlier history once when they have no server reward state yet", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: baseDocs(session(), {
    "users/u1/danceLearningSessions/a": { alignmentScore: 0.5, tsStart: NOW - 3000 },
    "users/u1/danceLearningSessions/b": { alignmentScore: 0.5, tsStart: NOW - 2000 },
    "users/u1/danceLearningSessions/c": { alignmentScore: 0.5, tsStart: NOW - 1000 },
  }) });
  await idx.rewardsFromSession(sessionEvent(c, session()));
  const state = c.db.data("users/u1/points/current");
  assert.equal(state.rewardedSessions, 4); // 3 older + this one
  assert.ok(Math.abs(state.alignmentSum - 2.4) < 1e-9);
  assert.equal(c.db.data("users/u1/sessionRewards/s1").newBadges.includes("FIRST_STEPS"), false);
});

test("rewardsFromSession ignores sessions without a numeric alignmentScore or without data", async () => {
  const c = h.reset({ docs: baseDocs() });
  await idx.rewardsFromSession(sessionEvent(c, session({ alignmentScore: "0.9" })));
  await idx.rewardsFromSession(sessionEvent(c, session({ alignmentScore: undefined })));
  await idx.rewardsFromSession({ params: { uid: "u1", sessionId: "s1" }, data: undefined });
  assert.deepEqual(c.db.log, []);
  assert.deepEqual(c.db.reads, []);
});

test("rewardsFromSession writes nothing when the user's account is gone", async () => {
  const c = h.reset({ docs: baseDocs() });
  c.db.docs.delete("users/u1");
  await idx.rewardsFromSession(sessionEvent(c, session()));
  assert.deepEqual(c.db.log, []);
});

test("rewardsFromSession records a capped summary but awards nothing past the daily limit", async (t) => {
  h.freezeTime(t, NOW);
  const state = { totalPoints: 1000, rewardedSessions: 40, alignmentSum: 30, dayKey: "2026-10-14", rewardedToday: rewards.MAX_REWARDED_SESSIONS_PER_DAY };
  const c = h.reset({ docs: baseDocs(session(), { "users/u1/points/current": state }) });
  await idx.rewardsFromSession(sessionEvent(c, session()));
  const reward = c.db.data("users/u1/sessionRewards/s1");
  assert.equal(reward.capped, true);
  assert.equal(reward.sessionPoints, 0);
  assert.deepEqual(c.db.data("users/u1/points/current"), state);
  assert.equal(c.db.has("leaderboardStats/u1"), false);
  assert.equal(c.db.has("users/u1/streaks/current"), false);
});

test("rewardsFromSession uses the phone's time-zone offset (clamped) to pick the local week", async (t) => {
  const sunday = Date.UTC(2026, 9, 18, 23, 0); // Sunday 23:00 UTC
  h.freezeTime(t, sunday);
  const c = h.reset({ docs: baseDocs(session({ tzOffsetMinutes: 120 })) });
  await idx.rewardsFromSession(sessionEvent(c, session({ tzOffsetMinutes: 120 })));
  assert.ok(c.db.has("users/u1/weeklyChallenges/2026-10-19")); // already Monday there
  assert.equal(c.db.data("users/u1/streaks/current").weekStart, "2026-10-19");

  const c2 = h.reset({ docs: baseDocs(session({ tzOffsetMinutes: 99999 })) });
  await idx.rewardsFromSession(sessionEvent(c2, session({ tzOffsetMinutes: 99999 })));
  assert.equal(c2.db.data("users/u1/points/current").dayKey, "2026-10-19"); // clamped to +14:00, not unbounded
});

test("rewardsFromSession advances an existing weekly challenge set, using the dance's style from the library", async (t) => {
  h.freezeTime(t, NOW);
  const set = rewards.generateChallenges({
    weekStart: WEEK, recentScores: [], availableDanceTypes: ["Salsa"], recentWeeklySessionCounts: [], nowMs: NOW, random: () => 0,
  });
  const c = h.reset({ docs: baseDocs(session(), { [`users/u1/weeklyChallenges/${WEEK}`]: rewards.challengeSetToDoc(set) }) });
  await idx.rewardsFromSession(sessionEvent(c, session()));
  const week = c.db.data(`users/u1/weeklyChallenges/${WEEK}`);
  assert.deepEqual(week.challenges.style_explorer.satisfiedStyles, ["Salsa"]);
  assert.equal(week.challenges.momentum_goal.progressCount, 1);
});

test("rewardsFromSession tolerates a session whose dance no longer exists", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: baseDocs(session({ analysis: { danceId: "gone" } })) });
  await idx.rewardsFromSession(sessionEvent(c, session({ analysis: { danceId: "gone" } })));
  assert.equal(c.db.data("users/u1/sessionRewards/s1").sessionPoints, 83);
  const noAnalysis = h.reset({ docs: baseDocs(session({ analysis: undefined })) });
  await idx.rewardsFromSession(sessionEvent(noAnalysis, session({ analysis: undefined })));
  assert.ok(noAnalysis.db.has("users/u1/sessionRewards/s1"));
});

test("rewardsFromSession rejects when the transaction commit fails so the event is retried", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: baseDocs() });
  c.db.onWrite = (op, p) => { if (p === "leaderboardStats/u1") throw new Error("commit failed"); };
  await assert.rejects(idx.rewardsFromSession(sessionEvent(c, session())), /commit failed/);
});

// ── weeklyChallenges ─────────────────────────────────────────────────────────

test("weeklyChallenges rejects signed-out callers", async () => {
  h.reset();
  await rejectsWith(idx.weeklyChallenges({ data: {} }), "unauthenticated");
  await rejectsWith(idx.weeklyChallenges({ auth: {}, data: {} }), "unauthenticated");
});

test("weeklyChallenges creates this week's set on first request, personalised from recent sessions and the feed", async (t) => {
  h.freezeTime(t, NOW);
  const docs = {
    "meta/danceCategories": { counts: { salsa: 3, hiphop: 0 }, labels: { salsa: "Salsa" } },
  };
  for (let i = 0; i < 4; i++) {
    docs[`users/u1/danceLearningSessions/s${i}`] = { alignmentScore: 0.7, timingConsistencyScore: 0.7, smoothnessScore: 0.7, tsStart: NOW - i * 1000 };
  }
  const c = h.reset({ docs });
  const result = await idx.weeklyChallenges({ auth: { uid: "u1" }, data: { tzOffsetMinutes: 0 } });
  assert.deepEqual(result, { weekStart: WEEK });
  const week = c.db.data(`users/u1/weeklyChallenges/${WEEK}`);
  assert.deepEqual(Object.keys(week.challenges).sort(), ["focus_drill", "momentum_goal", "style_explorer"]);
  assert.deepEqual(week.challenges.style_explorer.requiredStyles, ["Salsa"]); // hiphop has no dances
  assert.equal(week.challenges.focus_drill.type, "FOCUS_DRILL");
  assert.equal(week.bonusClaimed, false);
  assert.ok(week.updatedAt);
});

test("weeklyChallenges returns the existing set without rewriting it", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: { [`users/u1/weeklyChallenges/${WEEK}`]: { weekStart: WEEK, marker: true } } });
  const result = await idx.weeklyChallenges({ auth: { uid: "u1" }, data: {} });
  assert.deepEqual(result, { weekStart: WEEK });
  assert.deepEqual(c.db.log, []);
  assert.equal(c.db.transactions, 0);
});

test("weeklyChallenges works without data and uses the clamped time-zone offset for the local week", async (t) => {
  h.freezeTime(t, Date.UTC(2026, 9, 18, 23, 0)); // Sunday night UTC
  const c = h.reset();
  assert.deepEqual(await idx.weeklyChallenges({ auth: { uid: "u1" } }), { weekStart: "2026-10-12" });
  assert.deepEqual(await idx.weeklyChallenges({ auth: { uid: "u1" }, data: { tzOffsetMinutes: 120 } }), { weekStart: "2026-10-19" });
  assert.deepEqual(await idx.weeklyChallenges({ auth: { uid: "u2" }, data: { tzOffsetMinutes: "junk" } }), { weekStart: "2026-10-12" });
  assert.ok(c.db.has("users/u1/weeklyChallenges/2026-10-19"));
});

test("weeklyChallenges doesn't overwrite a set created by a concurrent request", async (t) => {
  h.freezeTime(t, NOW);
  const path = `users/u1/weeklyChallenges/${WEEK}`;
  const c = h.reset();
  let reads = 0;
  c.db.onRead = (p) => { if (p === path && ++reads === 2) c.db.docs.set(p, { weekStart: WEEK, marker: "other request" }); };
  const result = await idx.weeklyChallenges({ auth: { uid: "u1" }, data: {} });
  assert.deepEqual(result, { weekStart: WEEK });
  assert.equal(c.db.data(path).marker, "other request");
  assert.deepEqual(c.db.writesTo(path), []);
});

test("weeklyChallenges rejects when the set can't be saved", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset();
  c.db.onWrite = () => { throw new Error("write failed"); };
  await assert.rejects(idx.weeklyChallenges({ auth: { uid: "u1" }, data: {} }), /write failed/);
});

// ── leaderboardFromProfile ───────────────────────────────────────────────────

const profileEvent = (c, before, after, uid = "u1") => h.change(c.db, `users/${uid}`, before, after, { uid });
const boardSeed = (extra = {}) => ({
  "leaderboardStats/u1": { uid: "u1", displayName: "Old", photoUrl: null, totalPracticeSessions: 9, avgSimilarityScore: 0.5, streakDays: 3, totalActiveDays: 3, updatedAt: 1 },
  ...extra,
});

test("leaderboardFromProfile ignores account deletion and unchanged name/photo", async () => {
  const c = h.reset({ docs: boardSeed() });
  await idx.leaderboardFromProfile(profileEvent(c, { displayName: "A" }, undefined));
  await idx.leaderboardFromProfile(profileEvent(c, { displayName: "A", photoUrl: "https://x/y.png", bio: "1" }, { displayName: "A", photoUrl: "https://x/y.png", bio: "2" }));
  assert.deepEqual(c.db.log, []);
  assert.deepEqual(c.db.reads, []);
});

test("leaderboardFromProfile does nothing for users who aren't on the leaderboard", async () => {
  const c = h.reset();
  await idx.leaderboardFromProfile(profileEvent(c, { displayName: "A" }, { displayName: "B" }));
  await idx.leaderboardFromProfile(profileEvent(c, undefined, { displayName: "New" })); // profile created
  assert.deepEqual(c.db.log, []);
});

test("leaderboardFromProfile refreshes only name and photo for users without server-side reward state", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: boardSeed() });
  await idx.leaderboardFromProfile(profileEvent(c, { displayName: "Old" }, { displayName: "  Ann  ", photoUrl: "https://img.example/a.png" }));
  const board = c.db.data("leaderboardStats/u1");
  assert.equal(board.displayName, "Ann");
  assert.equal(board.photoUrl, "https://img.example/a.png");
  assert.equal(board.updatedAt, NOW);
  assert.equal(board.totalPracticeSessions, 9); // numbers kept
  assert.equal(board.avgSimilarityScore, 0.5);
});

test("leaderboardFromProfile rebuilds the entry from reward state when it exists", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: boardSeed({
    "users/u1/points/current": { rewardedSessions: 4, alignmentSum: 3 },
    "users/u1/streaks/current": { totalStreakActivities: 2 },
  }) });
  await idx.leaderboardFromProfile(profileEvent(c, { displayName: "Old" }, { displayName: "Ann" }));
  const board = c.db.data("leaderboardStats/u1");
  assert.deepEqual(board, {
    uid: "u1", displayName: "Ann", photoUrl: null, totalPracticeSessions: 4, avgSimilarityScore: 0.75,
    streakDays: 2, totalActiveDays: 2, updatedAt: NOW,
  });
});

test("leaderboardFromProfile reacts to a photo-only change and copes with a missing streak document", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: boardSeed({ "users/u1/points/current": { rewardedSessions: 2, alignmentSum: 1 } }) });
  await idx.leaderboardFromProfile(profileEvent(c, { displayName: "Ann" }, { displayName: "Ann", photoUrl: "https://img.example/new.png" }));
  const board = c.db.data("leaderboardStats/u1");
  assert.equal(board.photoUrl, "https://img.example/new.png");
  assert.equal(board.totalActiveDays, 0);
});

test("leaderboardFromProfile sanitises names and drops non-https photo URLs", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: boardSeed() });
  await idx.leaderboardFromProfile(profileEvent(c, { displayName: "Old" }, { displayName: "x".repeat(100), photoUrl: "http://insecure.example/a.png" }));
  const board = c.db.data("leaderboardStats/u1");
  assert.equal(board.displayName.length, 60);
  assert.equal(board.photoUrl, null);
  await idx.leaderboardFromProfile(profileEvent(c, { displayName: "x" }, { displayName: "   " }));
  assert.equal(c.db.data("leaderboardStats/u1").displayName, "User");
});

test("leaderboardFromProfile rejects when the leaderboard write fails", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: boardSeed() });
  c.db.onWrite = () => { throw new Error("write failed"); };
  await assert.rejects(idx.leaderboardFromProfile(profileEvent(c, { displayName: "Old" }, { displayName: "Ann" })), /write failed/);
});
