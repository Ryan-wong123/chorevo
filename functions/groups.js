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

module.exports = {
  storagePathsOfPosts,
  isInviteCode,
  invitationMessage,
  deadTokens,
  invitePushAllowance,
  INVITE_PUSHES_PER_HOUR,
};
