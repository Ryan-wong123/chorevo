// Pure helpers for the group functions in index.js.

/**
 * Storage paths of group-only videos and thumbnails uploaded by these posts.
 * Only files in the post author's own group folders: a post can't point the
 * cleanup at someone else's files.
 */
function storagePathsOfPosts(posts) {
  const paths = new Set();
  for (const post of posts) {
    if (typeof post.uid !== "string" || !/^[A-Za-z0-9]{1,128}$/.test(post.uid)) continue;
    for (const p of [post.storagePath, post.thumbnailPath]) {
      if (typeof p !== "string") continue;
      const m = /^group(?:Videos|Thumbnails)\/([^/]+)\/[^/]+$/.exec(p);
      if (m && m[1] === post.uid) paths.add(p);
    }
  }
  return [...paths];
}

/** Invite codes use this alphabet (see GroupModels.kt). */
const INVITE_CODE = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/;

function isInviteCode(code) {
  return typeof code === "string" && INVITE_CODE.test(code);
}

/** Push message (without tokens) telling the invitee about a group invitation. */
function invitationMessage(invitation) {
  const inviter = (invitation.inviterName || "Someone").slice(0, 60);
  const group = (invitation.groupName || "a group").slice(0, 60);
  return {
    notification: {
      title: "Group invitation",
      body: `${inviter} invited you to join ${group}`,
    },
    data: {
      type: "group_invitation",
      groupId: String(invitation.groupId || ""),
    },
    android: {
      priority: "high",
      notification: { channelId: "group_invites", tag: `invite_${invitation.groupId || ""}` },
    },
  };
}

/** Error codes meaning a token will never work again and should be removed. */
const DEAD_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

/** At most this many invitation pushes per inviter per hour. */
const INVITE_PUSHES_PER_HOUR = 20;

/**
 * Rate-limit bookkeeping for invitation pushes: given the inviter's counter
 * document data and the time, whether to send and the counter to store.
 */
function invitePushAllowance(counter, nowMs) {
  const hour = Math.floor(nowMs / 3_600_000);
  const sent = counter && counter.hour === hour ? Number(counter.sent) || 0 : 0;
  if (sent >= INVITE_PUSHES_PER_HOUR) return { allowed: false, counter: { hour, sent } };
  return { allowed: true, counter: { hour, sent: sent + 1 } };
}

/** Tokens to delete after a multicast send: those whose response failed with a dead-token code. */
function deadTokens(tokens, responses) {
  return tokens.filter((_, i) => {
    const r = responses[i];
    return r && !r.success && r.error && DEAD_TOKEN_CODES.has(r.error.code);
  });
}

// ── Group video links ────────────────────────────────────────────────────────

/** How long a group video or cover link works. */
const MEDIA_LINK_TTL_MS = 60 * 60 * 1000;
const MAX_MEDIA_POSTS = 50;
const DOC_ID = /^[A-Za-z0-9_-]{1,128}$/;

/** Validates a groupMediaUrls request: { groupId, postIds: [...] } (at most 50 ids). Null if invalid. */
function parseGroupMediaRequest(data) {
  if (!data || typeof data.groupId !== "string" || !DOC_ID.test(data.groupId)) return null;
  if (!Array.isArray(data.postIds) || data.postIds.length === 0 || data.postIds.length > MAX_MEDIA_POSTS) return null;
  if (!data.postIds.every((id) => typeof id === "string" && DOC_ID.test(id))) return null;
  return { groupId: data.groupId, postIds: [...new Set(data.postIds)] };
}

/** Who may see a group's posts (same as the Firestore rules): anyone for public groups, else members. */
function canSeeGroup(group, isMember) {
  return !!group && (group.visibility === "public" || isMember === true);
}

/**
 * The Storage paths of a video post's own files, only if they are in its
 * author's group folders and named after the post. Never anything else.
 */
function groupPostMediaPaths(postId, post) {
  if (!post || post.kind !== "video" || typeof post.uid !== "string" || !post.uid) return {};
  const paths = {};
  if (post.storagePath === `groupVideos/${post.uid}/${postId}.mp4`) paths.video = post.storagePath;
  if (post.thumbnailPath === `groupThumbnails/${post.uid}/${postId}.jpg`) paths.thumbnail = post.thumbnailPath;
  return paths;
}

/** Group videos and covers are never shared through permanent download links. */
function isGroupMediaPath(path) {
  return typeof path === "string" && /^group(Videos|Thumbnails)\/[^/]+\/[^/]+$/.test(path);
}

/**
 * Removes a file's Firebase download token, so its permanent download link
 * stops working. Returns true if there was one. Missing files are ignored.
 */
async function removeDownloadTokens(file) {
  let meta;
  try {
    [meta] = await file.getMetadata();
  } catch (err) {
    if (err && err.code === 404) return false;
    throw err;
  }
  if (!meta || !meta.metadata || !meta.metadata.firebaseStorageDownloadTokens) return false;
  await file.setMetadata({ metadata: { firebaseStorageDownloadTokens: null } });
  return true;
}

/**
 * Catch-up for video posts made before group videos used short-lived links:
 * removes their files' download tokens and the stored permanent URLs.
 * Safe to repeat (later runs find nothing to do).
 */
async function lockOldGroupMedia(db, bucket, { deleteField, logger = console } = {}) {
  const snap = await db.collectionGroup("posts").get();
  let locked = 0;
  for (const doc of snap.docs) {
    const post = doc.data();
    const paths = groupPostMediaPaths(doc.id, post);
    for (const path of Object.values(paths)) {
      try {
        if (await removeDownloadTokens(bucket.file(path))) locked += 1;
      } catch (err) {
        logger.warn?.("Couldn't lock group media", { path, error: String(err && err.message) });
      }
    }
    if (post.kind === "video" && (post.videoUrl || post.thumbnailUrl)) {
      await doc.ref.update({ videoUrl: deleteField(), thumbnailUrl: deleteField() });
    }
  }
  return { posts: snap.size, filesLocked: locked };
}

module.exports = {
  removeDownloadTokens,
  lockOldGroupMedia,
  MEDIA_LINK_TTL_MS,
  parseGroupMediaRequest,
  canSeeGroup,
  groupPostMediaPaths,
  isGroupMediaPath,
  storagePathsOfPosts,
  isInviteCode,
  invitationMessage,
  deadTokens,
  invitePushAllowance,
  INVITE_PUSHES_PER_HOUR,
};
