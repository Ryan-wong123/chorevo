const test = require("node:test");
const assert = require("node:assert");
const { leaderboardEntry } = require("../leaderboard");
const { userStoragePrefixes } = require("../account");

test("leaderboardEntry uses the profile and clamps forged values", () => {
  const e = leaderboardEntry("u1", { displayName: "  Ana  ", photoUrl: "https://lh3.googleusercontent.com/a" }, 12, 0.8, 5, 1000);
  assert.deepStrictEqual(e, {
    uid: "u1", displayName: "Ana", photoUrl: "https://lh3.googleusercontent.com/a",
    totalPracticeSessions: 12, avgSimilarityScore: 0.8, streakDays: 5, totalActiveDays: 5, updatedAt: 1000,
  });
  const forged = leaderboardEntry("u1", { displayName: "x".repeat(500), photoUrl: "javascript:alert(1)" }, 3, 99, 1e9, 1);
  assert.strictEqual(forged.displayName.length, 60);
  assert.strictEqual(forged.photoUrl, null);
  assert.strictEqual(forged.avgSimilarityScore, 1);
  assert.strictEqual(forged.totalActiveDays, 3); // can't exceed sessions
});

test("leaderboardEntry handles users without sessions or names", () => {
  const e = leaderboardEntry("u2", {}, 0, null, undefined, 1);
  assert.strictEqual(e.displayName, "User");
  assert.strictEqual(e.totalPracticeSessions, 0);
  assert.strictEqual(e.avgSimilarityScore, 0);
  assert.strictEqual(e.totalActiveDays, 0);
});

test("account deletion covers every per-user Storage folder", () => {
  const prefixes = userStoragePrefixes("u1");
  for (const folder of ["danceVideos", "danceThumbnails", "danceTemplates", "groupVideos", "groupThumbnails", "profilePhotos"]) {
    assert.ok(prefixes.includes(`${folder}/u1/`), folder);
  }
  assert.ok(prefixes.every((p) => p.endsWith("/u1/")));
});
