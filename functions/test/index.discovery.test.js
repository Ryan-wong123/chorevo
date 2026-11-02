// danceDerivedFields, revokeLinksWhenPrivate, danceLikeCount, refreshDiscovery.
const test = require("node:test");
const assert = require("node:assert/strict");
const h = require("./support/harness");
const { derivedFieldUpdates, trendingScore } = require("../discovery");
const { downloadUrl } = require("../privacy");

const idx = h.loadIndex();
const NOW = Date.UTC(2026, 9, 14, 12);
const CATS = "meta/danceCategories";
const dance = (extra = {}) => ({
  title: "Salsa Basics", danceType: "Salsa", educatorName: "Maria", educatorUid: "owner1",
  enabled: true, visibility: "public", createdAt: NOW - 86_400_000, ...extra,
});
const fullyDerived = (d) => ({ ...d, ...derivedFieldUpdates(d, NOW) });

// ── danceDerivedFields ───────────────────────────────────────────────────────

test("danceDerivedFields fills feed fields on a new dance and counts it in its category", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: { "danceLibrary/d1": dance() } });
  await idx.danceDerivedFields(h.change(c.db, "danceLibrary/d1", undefined, dance(), { danceId: "d1" }));
  const stored = c.db.data("danceLibrary/d1");
  assert.equal(stored.feedReady, true);
  assert.equal(stored.danceTypeKey, "salsa");
  assert.ok(stored.searchTokens.includes("sal"));
  assert.equal(typeof stored.shuffleKey, "number");
  assert.equal(stored.likeCount, 0);
  assert.equal(typeof stored.trendingScore, "number");
  assert.deepEqual(c.db.data(CATS), { counts: { salsa: 1 }, labels: { salsa: "Salsa" } });
});

test("danceDerivedFields writes nothing when the dance already has its derived fields (no update loop)", async () => {
  const d = fullyDerived(dance());
  const c = h.reset({ docs: { "danceLibrary/d1": d } });
  await idx.danceDerivedFields(h.change(c.db, "danceLibrary/d1", d, d, { danceId: "d1" }));
  assert.deepEqual(c.db.writesTo("danceLibrary/d1"), []);
  assert.deepEqual(c.db.writesTo(CATS), []); // chip unchanged too
});

test("danceDerivedFields moves the count between categories when the dance type changes", async () => {
  const before = fullyDerived(dance());
  const after = fullyDerived(dance({ danceType: "Hip Hop" }));
  const c = h.reset({ docs: { "danceLibrary/d1": after, [CATS]: { counts: { salsa: 1 }, labels: { salsa: "Salsa" } } } });
  await idx.danceDerivedFields(h.change(c.db, "danceLibrary/d1", before, after, { danceId: "d1" }));
  const cats = c.db.data(CATS);
  assert.equal(cats.counts.salsa, 0);
  assert.equal(cats.counts.hiphop, 1);
  assert.equal(cats.labels.hiphop, "Hip Hop");
});

test("danceDerivedFields on delete only decrements the category and does not update the gone document", async () => {
  const before = fullyDerived(dance());
  const c = h.reset({ docs: { [CATS]: { counts: { salsa: 2 }, labels: { salsa: "Salsa" } } } });
  await idx.danceDerivedFields(h.change(c.db, "danceLibrary/d1", before, undefined, { danceId: "d1" }));
  assert.equal(c.db.data(CATS).counts.salsa, 1);
  assert.deepEqual(c.db.writesTo("danceLibrary/d1"), []);
});

test("danceDerivedFields does not count private or disabled dances", async () => {
  const c = h.reset({ docs: { "danceLibrary/d1": dance({ visibility: "private" }) } });
  await idx.danceDerivedFields(h.change(c.db, "danceLibrary/d1", undefined, dance({ visibility: "private" }), { danceId: "d1" }));
  assert.equal(c.db.has(CATS), false);
  assert.equal(c.db.data("danceLibrary/d1").feedReady, false);
});

test("danceDerivedFields: a failing category update is logged but the dance's own fields are still written", async () => {
  const c = h.reset({ docs: { "danceLibrary/d1": dance() } });
  c.db.onWrite = (op, p) => { if (p === CATS) throw new Error("category write failed"); };
  await idx.danceDerivedFields(h.change(c.db, "danceLibrary/d1", undefined, dance(), { danceId: "d1" }));
  assert.equal(c.logger.entries.warn.length, 1);
  assert.equal(c.logger.entries.warn[0][1].danceId, "d1");
  assert.equal(c.db.data("danceLibrary/d1").feedReady, true);
});

test("danceDerivedFields rejects when the dance update itself fails so the event can be retried", async () => {
  const c = h.reset({ docs: { "danceLibrary/d1": dance() } });
  c.db.onWrite = (op, p) => { if (p === "danceLibrary/d1") throw new Error("boom"); };
  await assert.rejects(idx.danceDerivedFields(h.change(c.db, "danceLibrary/d1", undefined, dance(), { danceId: "d1" })), /boom/);
});

// ── revokeLinksWhenPrivate ───────────────────────────────────────────────────

const BUCKET = "chorevo-dea05.firebasestorage.app";
const vPath = "danceVideos/owner1/d1.mp4";
const tPath = "danceThumbnails/owner1/d1.jpg";
const mediaDance = (extra = {}) => dance({
  danceId: "d1",
  videoUrl: downloadUrl(BUCKET, vPath, "oldtoken"),
  thumbnailUrl: downloadUrl(BUCKET, tPath, "oldtoken"),
  ...extra,
});
const mediaFiles = () => ({
  [vPath]: { metadata: { firebaseStorageDownloadTokens: "oldtoken" } },
  [tPath]: { metadata: { firebaseStorageDownloadTokens: "oldtoken" } },
});

test("revokeLinksWhenPrivate rotates the video and cover tokens and stores the new URLs when a dance becomes private", async (t) => {
  h.freezeTime(t, NOW);
  const before = mediaDance();
  const after = mediaDance({ visibility: "private" });
  const c = h.reset({ docs: { "danceLibrary/d1": after }, files: mediaFiles() });
  await idx.revokeLinksWhenPrivate(h.change(c.db, "danceLibrary/d1", before, after, { danceId: "d1" }));
  const stored = c.db.data("danceLibrary/d1");
  const vToken = c.bucket.files.get(vPath).metadata.firebaseStorageDownloadTokens;
  assert.notEqual(vToken, "oldtoken");
  assert.notEqual(c.bucket.files.get(tPath).metadata.firebaseStorageDownloadTokens, "oldtoken");
  assert.equal(stored.videoUrl, downloadUrl(BUCKET, vPath, vToken));
  assert.doesNotMatch(stored.thumbnailUrl, /oldtoken/);
  assert.equal(stored.linksRevokedAt, NOW);
});

test("revokeLinksWhenPrivate ignores updates that are not a public-to-private change", async () => {
  const pub = mediaDance();
  const priv = mediaDance({ visibility: "private" });
  const c = h.reset({ docs: { "danceLibrary/d1": priv }, files: mediaFiles() });
  for (const [b, a] of [[pub, mediaDance({ title: "New" })], [priv, mediaDance({ visibility: "private", title: "New" })], [priv, pub]]) {
    await idx.revokeLinksWhenPrivate(h.change(c.db, "danceLibrary/d1", b, a, { danceId: "d1" }));
  }
  assert.deepEqual(c.bucket.metadataWrites, []);
  assert.deepEqual(c.db.writesTo("danceLibrary/d1"), []);
});

test("revokeLinksWhenPrivate only touches the owner's own files", async () => {
  const foreign = "danceVideos/someoneElse/x.mp4";
  const before = mediaDance({ videoUrl: downloadUrl(BUCKET, foreign, "t") });
  const after = { ...before, visibility: "private" };
  const c = h.reset({ docs: { "danceLibrary/d1": after }, files: { ...mediaFiles(), [foreign]: { metadata: { firebaseStorageDownloadTokens: "t" } } } });
  await idx.revokeLinksWhenPrivate(h.change(c.db, "danceLibrary/d1", before, after, { danceId: "d1" }));
  assert.equal(c.bucket.files.get(foreign).metadata.firebaseStorageDownloadTokens, "t");
  assert.equal(c.db.data("danceLibrary/d1").videoUrl, after.videoUrl);
});

test("revokeLinksWhenPrivate leaves linksRevokedAt unset when a file can't be updated, so the catch-up retries", async () => {
  const before = mediaDance();
  const after = mediaDance({ visibility: "private" });
  const c = h.reset({ docs: { "danceLibrary/d1": after }, files: mediaFiles() });
  c.bucket.failSetMetadata.add(vPath);
  await idx.revokeLinksWhenPrivate(h.change(c.db, "danceLibrary/d1", before, after, { danceId: "d1" }));
  const stored = c.db.data("danceLibrary/d1");
  assert.equal(stored.linksRevokedAt, undefined);
  assert.equal(stored.videoUrl, after.videoUrl); // failed file keeps its URL
  assert.doesNotMatch(stored.thumbnailUrl, /oldtoken/); // the other one rotated
  assert.equal(c.logger.entries.warn.length, 1); // the failure is logged
  assert.equal(c.logger.entries.warn[0][1].path, vPath);
});

test("revokeLinksWhenPrivate rejects when saving the new URLs fails so the retrying event runs again", async () => {
  const before = mediaDance();
  const after = mediaDance({ visibility: "private" });
  const c = h.reset({ docs: { "danceLibrary/d1": after }, files: mediaFiles() });
  c.db.onWrite = () => { throw new Error("write failed"); };
  await assert.rejects(idx.revokeLinksWhenPrivate(h.change(c.db, "danceLibrary/d1", before, after, { danceId: "d1" })), /write failed/);
});

// ── danceLikeCount ───────────────────────────────────────────────────────────

const likeEvent = (c, before, after, id = "d1", liker = "u1") =>
  h.change(c.db, `danceLibrary/${id}/likes/${liker}`, before, after, { danceId: id, likerUid: liker });

test("danceLikeCount recounts exactly and refreshes the trending score when a like is added", async (t) => {
  h.freezeTime(t, NOW);
  const created = NOW - 3 * 86_400_000;
  const c = h.reset({ docs: {
    "danceLibrary/d1": { createdAt: created, likeCount: 99 },
    "danceLibrary/d1/likes/u1": { uid: "u1" },
    "danceLibrary/d1/likes/u2": { uid: "u2" },
  } });
  await idx.danceLikeCount(likeEvent(c, undefined, { uid: "u2" }));
  const d = c.db.data("danceLibrary/d1");
  assert.equal(d.likeCount, 2); // exact, not stale 99 +/- 1
  assert.equal(d.trendingScore, trendingScore(2, created, NOW));
});

test("danceLikeCount is idempotent: replaying the same event gives the same result", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: { "danceLibrary/d1": { createdAt: NOW }, "danceLibrary/d1/likes/u1": { uid: "u1" } } });
  await idx.danceLikeCount(likeEvent(c, undefined, { uid: "u1" }));
  const first = c.db.data("danceLibrary/d1");
  await idx.danceLikeCount(likeEvent(c, undefined, { uid: "u1" }));
  assert.deepEqual(c.db.data("danceLibrary/d1"), first);
});

test("danceLikeCount handles an unlike (count drops)", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: { "danceLibrary/d1": { createdAt: NOW, likeCount: 1 } } });
  await idx.danceLikeCount(likeEvent(c, { uid: "u1" }, undefined));
  assert.equal(c.db.data("danceLibrary/d1").likeCount, 0);
});

test("danceLikeCount does nothing when an existing like is merely rewritten, or nothing is deleted", async () => {
  const c = h.reset({ docs: { "danceLibrary/d1": { createdAt: NOW, likeCount: 5 } } });
  await idx.danceLikeCount(likeEvent(c, { uid: "u1" }, { uid: "u1", extra: 1 }));
  await idx.danceLikeCount(likeEvent(c, undefined, undefined));
  assert.deepEqual(c.db.log, []);
  assert.deepEqual(c.db.reads, []);
});

test("danceLikeCount skips a dance that was deleted along with its likes", async () => {
  const c = h.reset();
  await idx.danceLikeCount(likeEvent(c, { uid: "u1" }, undefined, "gone"));
  assert.deepEqual(c.db.log, []);
});

// ── refreshDiscovery ─────────────────────────────────────────────────────────

const refreshSeed = () => ({
  "danceLibrary/d1": dance(), // never derived, no likeCount
  "danceLibrary/d1/likes/u1": { liked: true }, // old like without uid
  "danceLibrary/priv": mediaDance({ visibility: "private", danceId: "priv", videoUrl: downloadUrl(BUCKET, "danceVideos/owner1/priv.mp4", "old"), thumbnailUrl: undefined }),
  "groups/g1/posts/p1": { kind: "video", uid: "u9", storagePath: "groupVideos/u9/p1.mp4", videoUrl: "https://old" },
  "users/a": { displayName: "A", email: "a@x.com" },
  "users/b": { displayName: "B" },
});
const refreshFiles = () => ({
  "danceVideos/owner1/priv.mp4": { metadata: { firebaseStorageDownloadTokens: "old" } },
  "groupVideos/u9/p1.mp4": { metadata: { firebaseStorageDownloadTokens: "tok" } },
});

test("refreshDiscovery runs the backfill, refresh and every catch-up", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: refreshSeed(), files: refreshFiles() });
  await idx.refreshDiscovery();
  assert.equal(c.db.data("danceLibrary/d1/likes/u1").uid, "u1");
  assert.equal(c.db.data("meta/backfill").likeUids, true);
  assert.equal(c.db.data("danceLibrary/d1").likeCount, 1); // exact recount
  assert.equal(c.db.data("danceLibrary/d1").feedReady, true);
  assert.deepEqual(c.db.data(CATS).counts, { salsa: 1 });
  assert.equal(c.db.data(CATS).updatedAt, NOW);
  assert.ok(c.db.data("danceLibrary/priv").linksRevokedAt);
  assert.notEqual(c.bucket.files.get("danceVideos/owner1/priv.mp4").metadata.firebaseStorageDownloadTokens, "old");
  assert.equal(c.db.data("groups/g1/posts/p1").mediaLocked, true);
  assert.equal(c.bucket.files.get("groupVideos/u9/p1.mp4").metadata.firebaseStorageDownloadTokens, null);
  assert.equal("email" in c.db.data("users/a"), false);
  assert.equal(c.logger.entries.error.length, 0);
});

test("refreshDiscovery is idempotent: a second run does not redo the one-off migrations", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: refreshSeed(), files: refreshFiles() });
  await idx.refreshDiscovery();
  const stateAfterFirst = new Map(c.db.docs);
  c.db.log.length = 0;
  c.bucket.metadataWrites.length = 0;
  await idx.refreshDiscovery();
  assert.deepEqual(c.bucket.metadataWrites, []);
  assert.equal(c.db.writesTo("meta/backfill").length, 0);
  assert.equal(c.db.writesTo("danceLibrary/d1/likes/u1").length, 0);
  assert.deepEqual(c.db.data("danceLibrary/priv"), stateAfterFirst.get("danceLibrary/priv"));
  assert.deepEqual(c.db.data(CATS), stateAfterFirst.get(CATS));
});

test("refreshDiscovery logs a failing catch-up and still finishes the remaining work", async (t) => {
  h.freezeTime(t, NOW);
  const c = h.reset({ docs: refreshSeed(), files: refreshFiles() });
  const good = c.bucket;
  Object.defineProperty(c, "bucket", { get() { throw new Error("storage down"); }, configurable: true });
  t.after(() => Object.defineProperty(c, "bucket", { value: good, writable: true, configurable: true }));
  await idx.refreshDiscovery();
  assert.equal(c.logger.entries.error.length, 2);
  assert.match(c.logger.entries.error[0][0], /Old private dance links failed/);
  assert.match(c.logger.entries.error[1][0], /Old group media failed/);
  assert.equal("email" in c.db.data("users/a"), false); // later step still ran
  assert.equal(c.db.data("danceLibrary/d1").likeCount, 1); // earlier step ran
});

test("refreshDiscovery rejects when the core refresh fails so the scheduler retries", async () => {
  const c = h.reset({ docs: refreshSeed(), files: refreshFiles() });
  c.db.onWrite = (op, p) => { if (p === CATS) throw new Error("meta write failed"); };
  await assert.rejects(idx.refreshDiscovery(), /meta write failed/);
});
