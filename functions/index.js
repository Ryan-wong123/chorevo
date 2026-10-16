const { onRequest } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { parseDanceId, renderDancePage } = require("./render");

initializeApp();

const PUBLIC_HOST = "chorevo-dea05.web.app";

/**
 * Serves https://chorevo-dea05.web.app/dance/<id> (via the Hosting rewrite in
 * firebase.json). Renders the dance's own title/description into the Open
 * Graph tags so WhatsApp, Instagram, Facebook, Telegram, X, etc. show it in
 * their link previews; the page itself then opens the app.
 */
exports.danceShare = onRequest({ region: "us-central1", maxInstances: 10 }, async (req, res) => {
  const danceId = parseDanceId(req.path);
  const url = `https://${PUBLIC_HOST}${req.path}`;

  let dance = null;
  if (danceId) {
    try {
      const snap = await getFirestore().collection("danceLibrary").doc(danceId).get();
      if (snap.exists) dance = snap.data();
    } catch (err) {
      logger.error("Failed to load dance for share page", { danceId, errorCode: err.code, errorMessage: String(err && err.message) });
    }
  }

  res.set("Cache-Control", "public, max-age=300, s-maxage=600");
  res.status(200).send(renderDancePage(dance, url));
});

// ── Discovery: server-maintained feed fields ────────────────────────────────
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { derivedFieldUpdates, trendingScore } = require("./discovery");
const { countLikes, refreshAllDances, backfillLikeUidsOnce, updateCategoryCounts } = require("./maintenance");

/**
 * Keeps each dance's derived fields (feedReady, danceTypeKey, searchTokens,
 * shuffleKey, defaults) in step with its content, and the category chip
 * counts in step with what is in the feed. Writes only fields that changed,
 * so its own update doesn't loop.
 */
exports.danceDerivedFields = onDocumentWritten(
  { document: "danceLibrary/{danceId}", region: "us-central1" },
  async (event) => {
    const db = getFirestore();
    const before = event.data.before.exists ? event.data.before.data() : null;
    const after = event.data.after.exists ? event.data.after.data() : null;

    await updateCategoryCounts(db, before, after);
    if (!after) return;

    const updates = derivedFieldUpdates(after, Date.now());
    if (Object.keys(updates).length) await event.data.after.ref.update(updates);
  }
);

/**
 * Recounts a dance's likes (exactly, so retried events can't drift) and
 * refreshes its trending score whenever a like is added or removed.
 */
exports.danceLikeCount = onDocumentWritten(
  { document: "danceLibrary/{danceId}/likes/{likerUid}", region: "us-central1" },
  async (event) => {
    const danceRef = getFirestore().doc(`danceLibrary/${event.params.danceId}`);
    const dance = await danceRef.get();
    if (!dance.exists) return; // dance deleted along with its likes
    const likeCount = await countLikes(danceRef);
    await danceRef.update({
      likeCount,
      trendingScore: trendingScore(likeCount, dance.get("createdAt"), Date.now()),
    });
  }
);

/**
 * Ages trending scores and rebuilds the category counts every 6 hours. Also
 * migrates existing data: fills missing feed fields and like counts, and adds
 * uids to old likes (once).
 */
exports.refreshDiscovery = onSchedule(
  { schedule: "every 6 hours", region: "us-central1", timeoutSeconds: 540 },
  async () => {
    const db = getFirestore();
    await backfillLikeUidsOnce(db, { logger });
    await refreshAllDances(db, { logger });
  }
);

// ── Groups ───────────────────────────────────────────────────────────────────
const { onDocumentDeleted } = require("firebase-functions/v2/firestore");
const { getStorage } = require("firebase-admin/storage");
const { storagePathsOfPosts } = require("./groups");

/** Keeps groups/{id}.memberCount exact whenever someone joins or leaves. */
exports.groupMemberCount = onDocumentWritten(
  { document: "groups/{groupId}/members/{memberUid}", region: "us-central1" },
  async (event) => {
    const groupRef = getFirestore().doc(`groups/${event.params.groupId}`);
    const group = await groupRef.get();
    if (!group.exists) return; // group deleted; its members are being cleaned up
    const snap = await groupRef.collection("members").count().get();
    await groupRef.update({ memberCount: snap.data().count });
  }
);

/**
 * When a group is deleted, removes everything under it (members, posts,
 * replies), its invite code, and the group-only videos and thumbnails its
 * posts uploaded.
 */
exports.groupCleanup = onDocumentDeleted(
  { document: "groups/{groupId}", region: "us-central1", timeoutSeconds: 300 },
  async (event) => {
    const db = getFirestore();
    const groupRef = db.doc(`groups/${event.params.groupId}`);
    const posts = await groupRef.collection("posts").get();
    const paths = storagePathsOfPosts(posts.docs.map((d) => d.data()));

    const bucket = getStorage().bucket();
    await Promise.all(paths.map((p) => bucket.file(p).delete({ ignoreNotFound: true }).catch((err) =>
      logger.warn("Could not delete group file", { path: p, error: String(err && err.message) })
    )));

    const code = event.data?.get("inviteCode");
    if (code) await db.doc(`groupInvites/${code}`).delete();
    await db.recursiveDelete(groupRef);
  }
);
