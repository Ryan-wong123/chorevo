const { onRequest } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { parseDanceId, renderDancePage } = require("./render");

initializeApp();

// Cost guard: no function scales past 10 instances (abuse can't run up a bill).
const { setGlobalOptions } = require("firebase-functions/v2");
setGlobalOptions({ maxInstances: 10 });

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

  res.set("Cache-Control", "public, max-age=120, s-maxage=300");
  // Also set by Hosting (firebase.json); repeated for direct function URLs.
  res.set("X-Content-Type-Options", "nosniff");
  res.set("X-Frame-Options", "DENY");
  res.set("Referrer-Policy", "strict-origin-when-cross-origin");
  res.status(200).send(renderDancePage(dance, url));
});

// ── Discovery: server-maintained feed fields ────────────────────────────────
const { onDocumentWritten } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { derivedFieldUpdates, trendingScore } = require("./discovery");
const { countLikes, refreshAllDances, backfillLikeUidsOnce, updateCategoryCounts, removeProfileEmails } = require("./maintenance");

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

    // Never let a bad category value block the dance's own feed fields.
    try {
      await updateCategoryCounts(db, before, after);
    } catch (err) {
      logger.warn("Category count update failed", { danceId: event.params.danceId, error: String(err && err.message) });
    }
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
    // Rewriting an existing like (or a delete of nothing) changes no count.
    if (event.data.before.exists === event.data.after.exists) return;
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
    logger.info("Profile email cleanup", await removeProfileEmails(db));
  }
);

// ── Groups ───────────────────────────────────────────────────────────────────
const { onDocumentDeleted, onDocumentCreated } = require("firebase-functions/v2/firestore");
const { getStorage } = require("firebase-admin/storage");
const { getMessaging } = require("firebase-admin/messaging");
const { FieldValue } = require("firebase-admin/firestore");
const { storagePathsOfPosts, isInviteCode, invitationMessage, deadTokens, invitePushAllowance } = require("./groups");

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
/** Pushes a notification to the invited user's devices when they're invited to a group. */
exports.notifyGroupInvitation = onDocumentCreated(
  { document: "groupInvitations/{invitationId}", region: "us-central1" },
  async (event) => {
    const data = event.data?.data();
    if (!data?.inviteeUid || !data?.inviterUid || !data?.groupId) return;
    const db = getFirestore();

    // One push per invitation ID ever (deleting and re-sending doesn't notify
    // again), and at most a few per inviter per hour.
    const sentRef = db.doc(`pushLog/invite_${event.params.invitationId}`);
    const rateRef = db.doc(`pushRate/${data.inviterUid}`);
    const allowed = await db.runTransaction(async (tx) => {
      const [sent, rate] = await Promise.all([tx.get(sentRef), tx.get(rateRef)]);
      if (sent.exists) return false;
      const { allowed, counter } = invitePushAllowance(rate.exists ? rate.data() : null, Date.now());
      tx.set(rateRef, counter);
      if (allowed) tx.set(sentRef, { at: FieldValue.serverTimestamp() });
      return allowed;
    });
    if (!allowed) {
      logger.info("Invitation push skipped (duplicate or rate limit)", { inviterUid: data.inviterUid });
      return;
    }

    // Names come from the server's copy, not from what the inviter wrote.
    const [group, inviter, membership] = await Promise.all([
      db.doc(`groups/${data.groupId}`).get(),
      db.doc(`users/${data.inviterUid}`).get(),
      db.doc(`groups/${data.groupId}/members/${data.inviterUid}`).get(),
    ]);
    if (!group.exists || !membership.exists) return;
    const invitation = {
      groupId: data.groupId,
      inviteeUid: data.inviteeUid,
      groupName: group.get("name"),
      inviterName: inviter.get("displayName") || (inviter.get("username") ? `@${inviter.get("username")}` : "Someone"),
    };

    const tokenDocs = await db.collection(`users/${invitation.inviteeUid}/fcmTokens`).limit(20).get();
    const tokens = tokenDocs.docs.map((d) => d.id);
    if (tokens.length === 0) return;

    const result = await getMessaging().sendEachForMulticast({ ...invitationMessage(invitation), tokens });
    const dead = deadTokens(tokens, result.responses);
    await Promise.all(dead.map((t) => db.doc(`users/${invitation.inviteeUid}/fcmTokens/${t}`).delete()));
    logger.info("Group invitation push", { sent: result.successCount, failed: result.failureCount, removed: dead.length });
  }
);

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

    // Only this group's own invite document (a group can't name someone else's code).
    const code = event.data?.get("inviteCode");
    if (isInviteCode(code)) {
      const inviteRef = db.doc(`groupInvites/${code}`);
      const invite = await inviteRef.get();
      if (invite.exists && invite.get("groupId") === event.params.groupId) await inviteRef.delete();
    }

    // Pending invitations by username to this group.
    const invitations = await db.collection("groupInvitations").where("groupId", "==", event.params.groupId).get();
    const writer = db.bulkWriter();
    invitations.docs.forEach((d) => writer.delete(d.ref));
    await writer.close();

    await db.recursiveDelete(groupRef);
  }
);

// ── Leaderboard (server-computed) ─────────────────────────────────────────────
const { AggregateField } = require("firebase-admin/firestore");
const { leaderboardEntry } = require("./leaderboard");

/** Recomputes leaderboardStats/{uid} from the user's sessions, streak and profile. */
async function recomputeLeaderboard(uid) {
  const db = getFirestore();
  const userRef = db.doc(`users/${uid}`);
  const [user, agg, streak] = await Promise.all([
    userRef.get(),
    userRef.collection("danceLearningSessions")
      .aggregate({ sessions: AggregateField.count(), avg: AggregateField.average("alignmentScore") })
      .get(),
    userRef.collection("streaks").doc("current").get(),
  ]);
  if (!user.exists) return;
  const { sessions, avg } = agg.data();
  const entry = leaderboardEntry(uid, user.data(), sessions, avg, streak.get("totalStreakActivities"), Date.now());
  await db.doc(`leaderboardStats/${uid}`).set(entry);
}

exports.leaderboardFromSessions = onDocumentWritten(
  { document: "users/{uid}/danceLearningSessions/{sessionId}", region: "us-central1" },
  (event) => recomputeLeaderboard(event.params.uid)
);

exports.leaderboardFromStreak = onDocumentWritten(
  { document: "users/{uid}/streaks/{docId}", region: "us-central1" },
  (event) => recomputeLeaderboard(event.params.uid)
);

/** Name or photo changes show on the leaderboard (only for users already on it). */
exports.leaderboardFromProfile = onDocumentWritten(
  { document: "users/{uid}", region: "us-central1" },
  async (event) => {
    const before = event.data.before.exists ? event.data.before.data() : {};
    const after = event.data.after.exists ? event.data.after.data() : null;
    if (!after) return;
    if (before.displayName === after.displayName && before.photoUrl === after.photoUrl) return;
    const stats = await getFirestore().doc(`leaderboardStats/${event.params.uid}`).get();
    if (stats.exists) await recomputeLeaderboard(event.params.uid);
  }
);

// ── Account deletion ─────────────────────────────────────────────────────────
const functionsV1 = require("firebase-functions/v1");
const { userStoragePrefixes } = require("./account");

/**
 * When a user deletes their account (Firebase Auth), removes their data:
 * profile and everything under it, username, leaderboard entry, dances
 * (with likes and comments), likes and comments on others' dances, groups
 * they own, memberships, group posts and replies, invitations, and their
 * Storage files.
 */
exports.deleteUserData = functionsV1
  .region("us-central1")
  .runWith({ timeoutSeconds: 540, memory: "512MB" })
  .auth.user()
  .onDelete(async (user) => {
    const uid = user.uid;
    const db = getFirestore();
    const removeAll = async (query) => {
      const snap = await query.get();
      await Promise.all(snap.docs.map((d) => db.recursiveDelete(d.ref)));
      return snap.size;
    };

    // Groups they own (groupCleanup removes each group's posts and media).
    const memberships = await db.collectionGroup("members").where("uid", "==", uid).get();
    for (const m of memberships.docs) {
      const groupRef = m.ref.parent.parent;
      if (m.get("role") === "owner") await groupRef.delete();
      else await m.ref.delete();
    }

    const counts = {
      dances: await removeAll(db.collection("danceLibrary").where("educatorUid", "==", uid)),
      likes: await removeAll(db.collectionGroup("likes").where("uid", "==", uid)),
      comments: await removeAll(db.collectionGroup("comments").where("uid", "==", uid)),
      posts: await removeAll(db.collectionGroup("posts").where("uid", "==", uid)),
      replies: await removeAll(db.collectionGroup("replies").where("uid", "==", uid)),
      invitationsReceived: await removeAll(db.collection("groupInvitations").where("inviteeUid", "==", uid)),
      invitationsSent: await removeAll(db.collection("groupInvitations").where("inviterUid", "==", uid)),
      usernames: await removeAll(db.collection("usernames").where("uid", "==", uid)),
    };
    await db.doc(`leaderboardStats/${uid}`).delete();
    await db.recursiveDelete(db.doc(`users/${uid}`));
    await db.doc(`pushRate/${uid}`).delete();

    const bucket = getStorage().bucket();
    for (const prefix of userStoragePrefixes(uid)) {
      await bucket.deleteFiles({ prefix }).catch((err) =>
        logger.warn("Could not delete user files", { prefix, error: String(err && err.message) })
      );
    }
    logger.info("Deleted user data", { uid, ...counts });
  });
