// functions/maintenance.js: Firestore jobs shared by refreshDiscovery and scripts/backfill.js.
const test = require("node:test");
const assert = require("node:assert/strict");
const h = require("./support/harness");

h.install(); // stubs firebase-admin/firestore (FieldValue) before maintenance.js loads it
const m = require("../maintenance");
const { trendingScore, derivedFieldUpdates } = require("../discovery");

const NOW = Date.UTC(2026, 9, 14, 12);
const CATS = m.CATEGORIES_DOC;
const quiet = { info: () => {}, warn: () => {}, error: () => {} };
const dance = (extra = {}) => ({
  title: "Salsa Basics", danceType: "Salsa", educatorName: "Maria", enabled: true, visibility: "public",
  createdAt: NOW - 86_400_000, ...extra,
});
const settled = (d, extra = {}) => {
  const base = { likeCount: 0, ...d, ...extra };
  const filled = { ...base, ...derivedFieldUpdates(base, NOW) };
  return { ...filled, trendingScore: trendingScore(filled.likeCount, filled.createdAt, NOW) };
};

// ── countLikes ───────────────────────────────────────────────────────────────

test("countLikes returns the exact number of like documents for that dance only", async () => {
  const c = h.reset({ docs: {
    "danceLibrary/d1/likes/a": {}, "danceLibrary/d1/likes/b": {}, "danceLibrary/d2/likes/a": {}, "danceLibrary/d1": {},
  } });
  assert.equal(await m.countLikes(c.db.doc("danceLibrary/d1")), 2);
  assert.equal(await m.countLikes(c.db.doc("danceLibrary/none")), 0);
});

// ── refreshAllDances ─────────────────────────────────────────────────────────

test("refreshAllDances fills derived fields, exact like counts and trending scores, and rewrites the categories document", async () => {
  const c = h.reset({ docs: {
    "danceLibrary/d1": dance(),
    "danceLibrary/d1/likes/a": {}, "danceLibrary/d1/likes/b": {},
    "danceLibrary/d2": dance({ title: "Hip Hop 101", danceType: "Hip Hop", likeCount: 4 }),
    "danceLibrary/d3": dance({ visibility: "private" }),
    [CATS]: { counts: { stale: 9 }, labels: { stale: "Stale" } },
  } });
  const result = await m.refreshAllDances(c.db, { nowMs: NOW, logger: quiet });
  assert.deepEqual(result, { dances: 3, updated: 3 });
  const d1 = c.db.data("danceLibrary/d1");
  assert.equal(d1.likeCount, 2); // never had a count: counted exactly
  assert.equal(d1.trendingScore, trendingScore(2, d1.createdAt, NOW));
  assert.equal(d1.feedReady, true);
  assert.equal(c.db.data("danceLibrary/d2").likeCount, 4); // existing count trusted without recount
  assert.equal(c.db.data("danceLibrary/d3").feedReady, false);
  assert.deepEqual(c.db.data(CATS), { counts: { salsa: 1, hiphop: 1 }, labels: { salsa: "Salsa", hiphop: "Hip Hop" }, updatedAt: NOW });
});

test("refreshAllDances only writes dances whose fields changed and is idempotent", async () => {
  const c = h.reset({ docs: { "danceLibrary/d1": settled(dance()), "danceLibrary/d2": dance({ title: "Other" }) } });
  const first = await m.refreshAllDances(c.db, { nowMs: NOW, logger: quiet });
  assert.equal(first.updated, 1);
  assert.equal(c.db.writesTo("danceLibrary/d1").length, 0);
  c.db.log.length = 0;
  const second = await m.refreshAllDances(c.db, { nowMs: NOW, logger: quiet });
  assert.equal(second.updated, 0);
  assert.equal(c.db.log.filter((w) => w.path.startsWith("danceLibrary/")).length, 0);
});

test("refreshAllDances with recountLikes corrects drifted counts, without it a stored count is trusted", async () => {
  const docs = { "danceLibrary/d1": settled(dance(), { likeCount: 7 }), "danceLibrary/d1/likes/a": {} };
  const c = h.reset({ docs });
  await m.refreshAllDances(c.db, { nowMs: NOW, logger: quiet });
  assert.equal(c.db.data("danceLibrary/d1").likeCount, 7);
  await m.refreshAllDances(c.db, { nowMs: NOW, recountLikes: true, logger: quiet });
  assert.equal(c.db.data("danceLibrary/d1").likeCount, 1);
  assert.equal(c.db.data("danceLibrary/d1").trendingScore, trendingScore(1, dance().createdAt, NOW));
});

test("refreshAllDances ages the trending score as time passes", async () => {
  const c = h.reset({ docs: { "danceLibrary/d1": settled(dance()) } });
  const later = NOW + 10 * 86_400_000;
  const result = await m.refreshAllDances(c.db, { nowMs: later, logger: quiet });
  assert.equal(result.updated, 1);
  const d = c.db.data("danceLibrary/d1");
  assert.equal(d.trendingScore, trendingScore(0, d.createdAt, later));
  assert.ok(d.trendingScore < settled(dance()).trendingScore);
});

test("refreshAllDances with no dances writes an empty categories document", async () => {
  const c = h.reset();
  assert.deepEqual(await m.refreshAllDances(c.db, { nowMs: NOW, logger: quiet }), { dances: 0, updated: 0 });
  assert.deepEqual(c.db.data(CATS), { counts: {}, labels: {}, updatedAt: NOW });
});

test("refreshAllDances commits updates in batches of at most 400", async () => {
  const docs = {};
  for (let i = 0; i < 850; i++) docs[`danceLibrary/d${i}`] = dance({ title: `Dance ${i}` });
  const c = h.reset({ docs });
  const result = await m.refreshAllDances(c.db, { nowMs: NOW, logger: quiet });
  assert.equal(result.updated, 850);
  assert.deepEqual(c.db.batchSizes, [400, 400, 50]);
});

test("refreshAllDances logs a summary and propagates a failed write", async () => {
  const lines = [];
  const c = h.reset({ docs: { "danceLibrary/d1": dance() } });
  await m.refreshAllDances(c.db, { nowMs: NOW, logger: { info: (s) => lines.push(s) } });
  assert.deepEqual(lines, ["Refreshed 1 dances, updated 1."]);
  c.db.onWrite = () => { throw new Error("commit failed"); };
  await assert.rejects(m.refreshAllDances(c.db, { nowMs: NOW, logger: quiet }), /commit failed/);
});

// ── backfillLikeUids / backfillLikeUidsOnce ──────────────────────────────────

const likeDocs = () => ({
  "danceLibrary/d1/likes/alice": { liked: true },
  "danceLibrary/d1/likes/bob": { liked: true, uid: "bob", createdAt: 5 },
  "danceLibrary/d2/likes/carol": { liked: true },
  "groups/g1/posts/p1/likes/dave": { liked: true }, // not a dance like
});

test("backfillLikeUids adds the uid (the document id) and creation time to old dance likes only", async () => {
  const c = h.reset({ docs: likeDocs() });
  const result = await m.backfillLikeUids(c.db, { logger: quiet });
  assert.deepEqual(result, { likes: 4, updated: 2 });
  assert.deepEqual(c.db.data("danceLibrary/d1/likes/alice"), { liked: true, uid: "alice", createdAt: 1_700_000_000_000 });
  assert.equal(c.db.data("danceLibrary/d2/likes/carol").uid, "carol");
  assert.deepEqual(c.db.data("danceLibrary/d1/likes/bob"), { liked: true, uid: "bob", createdAt: 5 }); // untouched
  assert.equal(c.db.data("groups/g1/posts/p1/likes/dave").uid, undefined);
});

test("backfillLikeUids is idempotent and batches large backfills", async () => {
  const c = h.reset({ docs: likeDocs() });
  await m.backfillLikeUids(c.db, { logger: quiet });
  assert.equal((await m.backfillLikeUids(c.db, { logger: quiet })).updated, 0);

  const many = {};
  for (let i = 0; i < 401; i++) many[`danceLibrary/d1/likes/u${i}`] = { liked: true };
  const c2 = h.reset({ docs: many });
  await m.backfillLikeUids(c2.db, { logger: quiet });
  assert.deepEqual(c2.db.batchSizes, [400, 1]);
});

test("backfillLikeUidsOnce runs the backfill, records the marker and keeps the marker's other fields", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: { ...likeDocs(), "meta/backfill": { somethingElse: 1 } } });
  const result = await m.backfillLikeUidsOnce(c.db, { logger: quiet });
  assert.deepEqual(result, { likes: 4, updated: 2 });
  assert.deepEqual(c.db.data("meta/backfill"), { somethingElse: 1, likeUids: true, likeUidsAt: NOW });
});

test("backfillLikeUidsOnce does nothing once the marker is set", async () => {
  const c = h.reset({ docs: { ...likeDocs(), "meta/backfill": { likeUids: true } } });
  assert.equal(await m.backfillLikeUidsOnce(c.db, { logger: quiet }), null);
  assert.deepEqual(c.db.log, []);
  assert.equal(c.db.data("danceLibrary/d1/likes/alice").uid, undefined);
});

test("backfillLikeUidsOnce doesn't set the marker when the backfill fails, so it is retried", async () => {
  const c = h.reset({ docs: likeDocs() });
  c.db.onWrite = (op, p) => { if (p.includes("/likes/")) throw new Error("write failed"); };
  await assert.rejects(m.backfillLikeUidsOnce(c.db, { logger: quiet }), /write failed/);
  assert.equal(c.db.has("meta/backfill"), false);
});

// ── updateCategoryCounts ─────────────────────────────────────────────────────

const counts = (c) => c.db.data(CATS);

test("updateCategoryCounts counts a dance entering the feed and records its label", async () => {
  const c = h.reset();
  await m.updateCategoryCounts(c.db, null, dance());
  assert.deepEqual(counts(c), { counts: { salsa: 1 }, labels: { salsa: "Salsa" } });
  await m.updateCategoryCounts(c.db, undefined, dance({ title: "Another" }));
  assert.equal(counts(c).counts.salsa, 2);
});

test("updateCategoryCounts uncounts a dance leaving the feed (deleted, made private or disabled)", async () => {
  const c = h.reset({ docs: { [CATS]: { counts: { salsa: 3 }, labels: { salsa: "Salsa" } } } });
  await m.updateCategoryCounts(c.db, dance(), null);
  await m.updateCategoryCounts(c.db, dance(), dance({ visibility: "private" }));
  await m.updateCategoryCounts(c.db, dance(), dance({ enabled: false }));
  assert.equal(counts(c).counts.salsa, 0);
});

test("updateCategoryCounts moves the count when the chip changes, and updates the label to the newest spelling", async () => {
  const c = h.reset({ docs: { [CATS]: { counts: { salsa: 1 }, labels: { salsa: "Salsa" } } } });
  await m.updateCategoryCounts(c.db, dance(), dance({ danceType: "Hip Hop" }));
  assert.deepEqual(counts(c), { counts: { salsa: 0, hiphop: 1 }, labels: { salsa: "Salsa", hiphop: "Hip Hop" } });
  await m.updateCategoryCounts(c.db, null, dance({ danceType: "HIP-HOP" }));
  assert.equal(counts(c).labels.hiphop, "HIP-HOP");
  assert.equal(counts(c).counts.hiphop, 2);
});

test("updateCategoryCounts writes nothing when the chip is unchanged or the dance never counted", async () => {
  const c = h.reset();
  await m.updateCategoryCounts(c.db, dance(), dance({ title: "Renamed" }));
  await m.updateCategoryCounts(c.db, dance({ visibility: "private" }), dance({ visibility: "private", title: "x" }));
  await m.updateCategoryCounts(c.db, null, null);
  await m.updateCategoryCounts(c.db, null, dance({ danceType: "  " })); // blank type: no chip
  assert.deepEqual(c.db.log, []);
});

test("updateCategoryCounts treats differently spelled types as one chip", async () => {
  const c = h.reset();
  await m.updateCategoryCounts(c.db, null, dance({ danceType: "Hip Hop" }));
  await m.updateCategoryCounts(c.db, dance({ danceType: "Hip Hop" }), dance({ danceType: "hip-hop" }));
  assert.equal(counts(c).counts.hiphop, 1);
});

// ── removeProfileEmails ──────────────────────────────────────────────────────

test("removeProfileEmails strips stored emails and leaves other fields and users alone", async () => {
  const c = h.reset({ docs: {
    "users/a": { displayName: "A", email: "a@x.com" },
    "users/b": { displayName: "B", email: "b@x.com" },
    "users/c": { displayName: "C" },
    "users/d": { displayName: "D", email: null },
    "users/a/points/current": { email: "keep@x.com" }, // other collections untouched
  } });
  const result = await m.removeProfileEmails(c.db);
  assert.deepEqual(result, { profilesWithoutEmail: 2 });
  assert.deepEqual(c.db.data("users/a"), { displayName: "A" });
  assert.deepEqual(c.db.data("users/b"), { displayName: "B" });
  assert.deepEqual(c.db.data("users/c"), { displayName: "C" });
  assert.deepEqual(c.db.data("users/d"), { displayName: "D", email: null });
  assert.equal(c.db.data("users/a/points/current").email, "keep@x.com");
});

test("removeProfileEmails is idempotent and a no-op without users", async () => {
  const c = h.reset({ docs: { "users/a": { email: "a@x.com" } } });
  await m.removeProfileEmails(c.db);
  c.db.log.length = 0;
  assert.deepEqual(await m.removeProfileEmails(c.db), { profilesWithoutEmail: 0 });
  assert.deepEqual(c.db.log, []);
  assert.deepEqual(await m.removeProfileEmails(h.reset().db), { profilesWithoutEmail: 0 });
});

test("removeProfileEmails batches in groups of 400", async () => {
  const docs = {};
  for (let i = 0; i < 801; i++) docs[`users/u${i}`] = { email: `u${i}@x.com` };
  const c = h.reset({ docs });
  assert.equal((await m.removeProfileEmails(c.db)).profilesWithoutEmail, 801);
  assert.deepEqual(c.db.batchSizes, [400, 400, 1]);
  assert.ok(c.db.paths("users/").every((p) => !("email" in c.db.data(p))));
});
