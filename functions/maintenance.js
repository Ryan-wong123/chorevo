// Firestore jobs shared by the scheduled function and the one-off backfill
// script (scripts/backfill.js).
const { FieldValue } = require("firebase-admin/firestore");
const {
  derivedFieldUpdates,
  trendingScore,
  buildCategories,
  categoryContribution,
} = require("./discovery");

const CATEGORIES_DOC = "meta/danceCategories";
const BATCH_LIMIT = 400;

/** Writes queued updates in batches under Firestore's 500-operation limit. */
async function commitInBatches(db, writes) {
  for (let i = 0; i < writes.length; i += BATCH_LIMIT) {
    const batch = db.batch();
    for (const [ref, data] of writes.slice(i, i + BATCH_LIMIT)) batch.update(ref, data);
    await batch.commit();
  }
}

/** Exact like count for one dance (server-side count(), idempotent). */
async function countLikes(danceRef) {
  const snap = await danceRef.collection("likes").count().get();
  return snap.data().count;
}

/**
 * Recomputes the trending score (and, for the backfill, every derived field
 * and the exact like count) for all dances, then rewrites the categories
 * document from scratch so incremental counts can't drift for long.
 */
async function refreshAllDances(db, { nowMs = Date.now(), recountLikes = false, logger = console } = {}) {
  const snap = await db.collection("danceLibrary").get();
  const writes = [];
  const all = [];

  for (const doc of snap.docs) {
    const dance = doc.data();
    const updates = derivedFieldUpdates(dance, nowMs);

    let likeCount = typeof dance.likeCount === "number" ? dance.likeCount : 0;
    if (recountLikes) {
      likeCount = await countLikes(doc.ref);
      if (likeCount !== dance.likeCount) updates.likeCount = likeCount;
    }
    const score = trendingScore(likeCount, dance.createdAt, nowMs);
    if (typeof dance.trendingScore !== "number" || Math.abs(dance.trendingScore - score) > 1e-9) {
      updates.trendingScore = score;
    }

    if (Object.keys(updates).length) writes.push([doc.ref, updates]);
    all.push({ ...dance, ...updates });
  }

  await commitInBatches(db, writes);
  await db.doc(CATEGORIES_DOC).set({ ...buildCategories(all), updatedAt: nowMs });
  logger.info?.(`Refreshed ${snap.size} dances, updated ${writes.length}.`);
  return { dances: snap.size, updated: writes.length };
}

/**
 * Old like documents only hold { liked: true }; the liked-videos query needs
 * the liker's uid on the document. The document id is the uid.
 */
async function backfillLikeUids(db, { logger = console } = {}) {
  const snap = await db.collectionGroup("likes").get();
  const writes = snap.docs
    .filter((doc) => doc.ref.parent.parent?.parent?.id === "danceLibrary" && !doc.get("uid"))
    .map((doc) => [doc.ref, { uid: doc.id, createdAt: doc.createTime.toMillis() }]);
  await commitInBatches(db, writes);
  logger.info?.(`Added uid to ${writes.length} of ${snap.size} likes.`);
  return { likes: snap.size, updated: writes.length };
}

/**
 * Adjusts category counts when a dance enters, leaves or changes chip. A merge
 * write with increments needs no transaction; the label keeps the latest
 * spelling until the scheduled refresh picks the most common one.
 */
async function updateCategoryCounts(db, before, after) {
  const was = categoryContribution(before);
  const now = categoryContribution(after);
  if (was?.key === now?.key) return;

  const counts = {};
  if (was) counts[was.key] = FieldValue.increment(-1);
  if (now) counts[now.key] = FieldValue.increment(1);
  const data = { counts };
  if (now) data.labels = { [now.key]: now.label };
  await db.doc(CATEGORIES_DOC).set(data, { merge: true });
}

module.exports = {
  CATEGORIES_DOC,
  countLikes,
  refreshAllDances,
  backfillLikeUids,
  updateCategoryCounts,
};
