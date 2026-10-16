// Leaderboard stats are computed here, from the user's synced sessions and
// streak, so clients can't write their own rank (leaderboardStats is
// read-only in firestore.rules).

function clampScore(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return 0;
  return Math.min(1, Math.max(0, n));
}

function clampCount(value) {
  const n = Math.floor(Number(value));
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/**
 * The leaderboardStats document for a user.
 * @param {object} user users/{uid} data (displayName, photoUrl)
 * @param {number} sessions number of synced practice sessions
 * @param {number|null} avgScore average alignmentScore (0..1), null without sessions
 * @param {number} streakActivities streaks/current.totalStreakActivities
 */
function leaderboardEntry(uid, user, sessions, avgScore, streakActivities, nowMs) {
  const totalPracticeSessions = clampCount(sessions);
  // A practice day needs a practice session, so days can't exceed sessions.
  const totalActiveDays = Math.min(clampCount(streakActivities), totalPracticeSessions);
  const name = typeof user?.displayName === "string" ? user.displayName.trim().slice(0, 60) : "";
  const photo = typeof user?.photoUrl === "string" && /^https:\/\//.test(user.photoUrl) && user.photoUrl.length <= 1024
    ? user.photoUrl
    : null;
  return {
    uid,
    displayName: name || "User",
    photoUrl: photo,
    totalPracticeSessions,
    avgSimilarityScore: totalPracticeSessions ? clampScore(avgScore) : 0,
    // Legacy field kept equal to totalActiveDays.
    streakDays: totalActiveDays,
    totalActiveDays,
    updatedAt: nowMs,
  };
}

module.exports = { leaderboardEntry, clampScore };
