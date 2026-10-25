const { onRequest, onCall, HttpsError } = require("firebase-functions/v2/https");
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
const { onDocumentWritten, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { randomUUID } = require("node:crypto");
const { becamePrivate, rotateDanceLinks, revokeOldPrivateLinks } = require("./privacy");
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
 * When a dance is made private, gives its video and cover new download tokens
 * so links that were already copied or shared stop working, and stores the
 * new URLs on the dance for its owner. Writes only the URL fields, so the
 * follow-up update (still private) doesn't trigger it again.
 */
exports.revokeLinksWhenPrivate = onDocumentUpdated(
  // Retried on failure: once a token is rotated the old URL is dead, so the
  // new one must reach the dance. Running again just rotates once more.
  { document: "danceLibrary/{danceId}", region: "us-central1", retry: true },
  async (event) => {
    const before = event.data.before.data();
    const after = event.data.after.data();
    if (!becamePrivate(before, after)) return;

    const updates = await rotateDanceLinks(getStorage().bucket(), after, { newToken: randomUUID, logger });
    await event.data.after.ref.update(updates);
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
    // Dances made private before revokeLinksWhenPrivate existed (runs once each).
    logger.info("Old private dance links", await revokeOldPrivateLinks(db, getStorage().bucket(), { newToken: randomUUID, logger }));
    // Group videos posted before they used short-lived links (runs once each).
    logger.info("Old group media", await lockOldGroupMedia(db, getStorage().bucket(), { deleteField: FieldValue.delete, logger }));
    logger.info("Profile email cleanup", await removeProfileEmails(db));
  }
);

// ── Groups ───────────────────────────────────────────────────────────────────
const { onDocumentDeleted, onDocumentCreated } = require("firebase-functions/v2/firestore");
const { getStorage } = require("firebase-admin/storage");
const { getMessaging } = require("firebase-admin/messaging");
const { FieldValue } = require("firebase-admin/firestore");
const {
  storagePathsOfPosts, isInviteCode, invitationMessage, deadTokens, invitePushAllowance,
  MEDIA_LINK_TTL_MS, parseGroupMediaRequest, canSeeGroup, groupPostMediaPaths, removeDownloadTokens, lockOldGroupMedia,
} = require("./groups");

/**
 * Short-lived links for group post videos and covers. Group media has no
 * permanent download link (see lockGroupPostMedia), so the app asks here for
 * links that expire after an hour, for people who can see the group (anyone
 * signed in for public groups, members for private ones).
 */
exports.groupMediaUrls = onCall({ region: "us-central1" }, async (request) => {
  const uid = request.auth && request.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const req = parseGroupMediaRequest(request.data);
  if (!req) throw new HttpsError("invalid-argument", "Expected a group id and 1-50 post ids.");

  const db = getFirestore();
  const groupRef = db.doc(`groups/${req.groupId}`);
  const [group, membership] = await Promise.all([groupRef.get(), groupRef.collection("members").doc(uid).get()]);
  if (!group.exists || !canSeeGroup(group.data(), membership.exists)) {
    throw new HttpsError("permission-denied", "You can't see this group.");
  }

  const bucket = getStorage().bucket();
  const expiresAt = Date.now() + MEDIA_LINK_TTL_MS;
  const sign = async (path) => {
    const [url] = await bucket.file(path).getSignedUrl({ version: "v4", action: "read", expires: expiresAt });
    return url;
  };
  const posts = await db.getAll(...req.postIds.map((id) => groupRef.collection("posts").doc(id)));
  const urls = {};
  for (const post of posts) {
    if (!post.exists) continue;
    const paths = groupPostMediaPaths(post.id, post.data());
    if (!paths.video) continue;
    urls[post.id] = {
      videoUrl: await sign(paths.video),
      thumbnailUrl: paths.thumbnail ? await sign(paths.thumbnail) : null,
      expiresAt,
    };
  }
  return { urls };
});

/**
 * When a video post is created, removes its files' permanent download links,
 * so the video can only be played through groupMediaUrls.
 */
exports.lockGroupPostMedia = onDocumentCreated(
  { document: "groups/{groupId}/posts/{postId}", region: "us-central1", retry: true },
  async (event) => {
    const paths = groupPostMediaPaths(event.params.postId, event.data && event.data.data());
    const bucket = getStorage().bucket();
    for (const path of Object.values(paths)) await removeDownloadTokens(bucket.file(path));
  }
);

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

// ── Rewards and leaderboard (server-computed) ─────────────────────────────────
// Points, badges, the weekly streak, weekly challenges and leaderboard stats
// are all derived here from synced practice sessions. Clients can only read
// them (firestore.rules). Logic: functions/rewards.js and leaderboard.js.
const { AggregateField } = require("firebase-admin/firestore");
const { leaderboardEntry } = require("./leaderboard");
const rewards = require("./rewards");

const userDoc = (db, uid, path) => db.doc(`users/${uid}/${path}`);

/** Dance styles live in the feed (labels of categories with dances). */
async function availableDanceTypes(db) {
  const snap = await db.doc("meta/danceCategories").get();
  const counts = snap.get("counts") || {};
  const labels = snap.get("labels") || {};
  return Object.keys(counts).filter((k) => Number(counts[k]) > 0).map((k) => labels[k] || k);
}

/** A fresh challenge set for the user's current week, from their recent sessions. */
async function generateWeek(db, uid, day, tzOffsetMinutes, nowMs) {
  const recent = await db.collection(`users/${uid}/danceLearningSessions`)
    .orderBy("tsStart", "desc").limit(40).get();
  const rows = recent.docs.map((d) => d.data()).filter((s) => typeof s.alignmentScore === "number");
  return rewards.generateChallenges({
    weekStart: day.weekStart,
    recentScores: rows.slice(0, 10),
    availableDanceTypes: await availableDanceTypes(db),
    recentWeeklySessionCounts: rewards.weeklySessionCounts(
      rows.map((s) => Number(s.tsStart) || 0).filter(Boolean), tzOffsetMinutes, day.weekStart
    ),
    nowMs,
  });
}

/** Writes leaderboardStats/{uid} from the reward state (rewarded sessions, streak). */
function leaderboardFromState(uid, user, state, streak, nowMs) {
  const sessions = Number(state && state.rewardedSessions) || 0;
  const avg = sessions ? (Number(state.alignmentSum) || 0) / sessions : null;
  return leaderboardEntry(uid, user || {}, sessions, avg, streak && streak.totalStreakActivities, nowMs);
}

/**
 * Rewards one newly synced practice session: points, badges, streak, weekly
 * challenge progress and the leaderboard, in one transaction, exactly once
 * per session. The result goes to users/{uid}/sessionRewards/{sessionId},
 * which the app listens to for the celebration dialogs.
 */
exports.rewardsFromSession = onDocumentCreated(
  { document: "users/{uid}/danceLearningSessions/{sessionId}", region: "us-central1" },
  async (event) => {
    const { uid, sessionId } = event.params;
    const session = event.data?.data();
    if (!session || typeof session.alignmentScore !== "number") return;
    const db = getFirestore();
    const nowMs = Date.now();
    const tz = rewards.safeOffsetMinutes(session.tzOffsetMinutes);
    const day = rewards.localDay(nowMs, tz);

    // Reads that can't be done inside the transaction (queries), done first.
    const danceId = session.analysis && session.analysis.danceId;
    const [dance, existingWeek] = await Promise.all([
      typeof danceId === "string" && danceId ? db.doc(`danceLibrary/${danceId}`).get() : null,
      userDoc(db, uid, `weeklyChallenges/${day.weekStart}`).get(),
    ]);
    const danceType = dance && dance.exists ? dance.get("danceType") : "";
    const freshWeek = existingWeek.exists ? null : await generateWeek(db, uid, day, tz, nowMs);
    // Users with history from before server rewards: count it once.
    const stateSnap = await userDoc(db, uid, "points/current").get();
    let seed = null;
    if (typeof stateSnap.get("rewardedSessions") !== "number") {
      const agg = await db.collection(`users/${uid}/danceLearningSessions`)
        .aggregate({ n: AggregateField.count(), sum: AggregateField.sum("alignmentScore") }).get();
      const { n, sum } = agg.data();
      seed = { sessions: Math.max(0, n - 1), alignmentSum: Math.max(0, (Number(sum) || 0) - session.alignmentScore) };
    }

    const refs = {
      reward: userDoc(db, uid, `sessionRewards/${sessionId}`),
      state: userDoc(db, uid, "points/current"),
      achievements: userDoc(db, uid, "achievements/current"),
      streak: userDoc(db, uid, "streaks/current"),
      week: userDoc(db, uid, `weeklyChallenges/${day.weekStart}`),
      user: db.doc(`users/${uid}`),
      leaderboard: db.doc(`leaderboardStats/${uid}`),
    };

    await db.runTransaction(async (tx) => {
      const [reward, state, achievements, streak, week, user] = await Promise.all([
        tx.get(refs.reward), tx.get(refs.state), tx.get(refs.achievements),
        tx.get(refs.streak), tx.get(refs.week), tx.get(refs.user),
      ]);
      if (reward.exists || !user.exists) return; // already rewarded, or account gone

      const challengeSet = week.exists ? rewards.challengeSetFromDoc(week.data()) : freshWeek;
      const result = rewards.applySession({
        session, danceType, nowMs, seed,
        state: state.exists ? state.data() : null,
        achievements: achievements.exists ? achievements.data() : null,
        streak: streak.exists ? streak.data() : null,
        challengeSet,
      });

      tx.set(refs.reward, { sessionId, ...result.summary, createdAt: nowMs });
      if (result.capped) return;
      tx.set(refs.state, { ...result.state, lastUpdated: FieldValue.serverTimestamp() }, { merge: true });
      tx.set(refs.achievements, { ...result.achievements, lastUpdated: FieldValue.serverTimestamp() }, { merge: true });
      tx.set(refs.streak, { ...result.streak, updatedAt: FieldValue.serverTimestamp() });
      if (result.challengeSet) {
        tx.set(refs.week, { ...rewards.challengeSetToDoc(result.challengeSet), updatedAt: FieldValue.serverTimestamp() });
      }
      tx.set(refs.leaderboard, leaderboardFromState(uid, user.data(), result.state, result.streak, nowMs));
    });
  }
);

/**
 * This week's challenges for the signed-in user, created on first request.
 * Called by the app when it opens the challenges (data: { tzOffsetMinutes }).
 */
exports.weeklyChallenges = onCall({ region: "us-central1" }, async (request) => {
  const uid = request.auth && request.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const db = getFirestore();
  const nowMs = Date.now();
  const tz = rewards.safeOffsetMinutes(request.data && request.data.tzOffsetMinutes);
  const day = rewards.localDay(nowMs, tz);
  const ref = userDoc(db, uid, `weeklyChallenges/${day.weekStart}`);
  const existing = await ref.get();
  if (existing.exists) return { weekStart: day.weekStart };
  const set = await generateWeek(db, uid, day, tz, nowMs);
  await db.runTransaction(async (tx) => {
    // Another request (or a session reward) may have created it meanwhile.
    if ((await tx.get(ref)).exists) return;
    tx.set(ref, { ...rewards.challengeSetToDoc(set), updatedAt: FieldValue.serverTimestamp() });
  });
  return { weekStart: day.weekStart };
});

/** Name or photo changes show on the leaderboard (only for users already on it). */
exports.leaderboardFromProfile = onDocumentWritten(
  { document: "users/{uid}", region: "us-central1" },
  async (event) => {
    const before = event.data.before.exists ? event.data.before.data() : {};
    const after = event.data.after.exists ? event.data.after.data() : null;
    if (!after) return;
    if (before.displayName === after.displayName && before.photoUrl === after.photoUrl) return;
    const db = getFirestore();
    const uid = event.params.uid;
    const [stats, state, streak] = await Promise.all([
      db.doc(`leaderboardStats/${uid}`).get(),
      userDoc(db, uid, "points/current").get(),
      userDoc(db, uid, "streaks/current").get(),
    ]);
    if (!stats.exists) return;
    if (typeof state.get("rewardedSessions") !== "number") {
      // Not rewarded server-side yet: keep the numbers, refresh name and photo.
      const entry = leaderboardEntry(uid, after, 0, null, 0, Date.now());
      await stats.ref.update({ displayName: entry.displayName, photoUrl: entry.photoUrl, updatedAt: entry.updatedAt });
      return;
    }
    await stats.ref.set(leaderboardFromState(uid, after, state.data(), streak.exists ? streak.data() : null, Date.now()));
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
