const test = require("node:test");
const assert = require("node:assert");
const d = require("../discovery");

test("danceTypeKey groups spellings", () => {
  assert.strictEqual(d.danceTypeKey("Hip Hop"), "hiphop");
  assert.strictEqual(d.danceTypeKey("hip-hop"), "hiphop");
  assert.strictEqual(d.danceTypeKey("K-Pop"), "kpop");
  assert.strictEqual(d.danceTypeKey("  "), null);
});

test("searchTokens holds word prefixes from title, type and creator", () => {
  const tokens = d.searchTokens({ title: "Salsa Basics", danceType: "Latin", educatorName: "Maria" });
  for (const t of ["s", "sal", "salsa", "bas", "basics", "latin", "mar", "maria"]) {
    assert.ok(tokens.includes(t), t);
  }
  assert.ok(!tokens.includes("alsa"));
});

test("searchTokens caps long words at 15 characters", () => {
  const tokens = d.searchTokens({ title: "Supercalifragilisticexpialidocious" });
  assert.ok(tokens.includes("supercalifragil"));
  assert.ok(!tokens.some((t) => t.length > d.MAX_TOKEN_LENGTH));
});

test("feedReady needs enabled, public and a ready template", () => {
  assert.strictEqual(d.isFeedReady({ enabled: true }), true); // missing status/visibility = ready/public
  assert.strictEqual(d.isFeedReady({ enabled: true, visibility: "private" }), false);
  assert.strictEqual(d.isFeedReady({ enabled: true, templateGenerationStatus: "queued" }), false);
  assert.strictEqual(d.isFeedReady({ enabled: false }), false);
  assert.strictEqual(d.isFeedReady({}), false);
});

test("trendingScore favours likes and decays with age", () => {
  const now = 100 * 86_400_000;
  assert.ok(d.trendingScore(10, now, now) > d.trendingScore(2, now, now));
  assert.ok(d.trendingScore(5, now, now) > d.trendingScore(5, now - 30 * 86_400_000, now));
});

test("derivedFieldUpdates fills missing fields once, then is stable", () => {
  const now = Date.now();
  const dance = { enabled: true, title: "Groove", danceType: "Hip Hop", createdAt: now };
  const updates = d.derivedFieldUpdates(dance, now, () => 0.42);
  assert.strictEqual(updates.visibility, "public");
  assert.strictEqual(updates.feedReady, true);
  assert.strictEqual(updates.danceTypeKey, "hiphop");
  assert.strictEqual(updates.shuffleKey, 0.42);
  assert.strictEqual(updates.likeCount, 0);
  assert.strictEqual(typeof updates.trendingScore, "number");

  // Writing them back produces no further updates (no trigger loop), even later.
  assert.deepStrictEqual(d.derivedFieldUpdates({ ...dance, ...updates }, now + 3_600_000), {});
});

test("derivedFieldUpdates reacts to content changes", () => {
  const now = Date.now();
  const base = { enabled: true, title: "Groove", danceType: "Hip Hop", createdAt: now };
  const settled = { ...base, ...d.derivedFieldUpdates(base, now) };
  const updates = d.derivedFieldUpdates({ ...settled, visibility: "private", danceType: "Ballet" }, now);
  assert.strictEqual(updates.feedReady, false);
  assert.strictEqual(updates.danceTypeKey, "ballet");
  assert.ok(updates.searchTokens.includes("ballet"));
  assert.strictEqual(updates.shuffleKey, undefined); // set once only
});

test("buildCategories counts feed dances and picks the most common spelling", () => {
  const cats = d.buildCategories([
    { enabled: true, danceType: "hip hop" },
    { enabled: true, danceType: "hip hop" },
    { enabled: true, danceType: "Hip-Hop" },
    { enabled: true, danceType: "Ballet", visibility: "private" },
    { enabled: true, danceType: "" },
  ]);
  assert.deepStrictEqual(cats.counts, { hiphop: 3 });
  assert.strictEqual(cats.labels.hiphop, "hip hop");
});

test("searchTrigrams breaks words into 3-letter pieces for typo matching", () => {
  const grams = d.searchTrigrams({ title: "Salsa Go", danceType: "Hip Hop" });
  for (const g of ["sal", "als", "lsa", "go", "hip", "hop", "iph", "pho"]) assert.ok(grams.includes(g), g);
  // A misspelling shares pieces with the real word.
  assert.ok(["hip", "iph"].some((g) => grams.includes(g)));
});
