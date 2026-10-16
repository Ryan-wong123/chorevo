// Pure helpers for the group functions in index.js.

/** Storage paths of group-only videos and thumbnails uploaded by these posts. */
function storagePathsOfPosts(posts) {
  const paths = new Set();
  for (const post of posts) {
    for (const p of [post.storagePath, post.thumbnailPath]) {
      // Only files in the group folders; never touch the main danceVideos/.
      if (typeof p === "string" && /^group(Videos|Thumbnails)\//.test(p)) paths.add(p);
    }
  }
  return [...paths];
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
  "messaging/invalid-argument",
]);

/** Tokens to delete after a multicast send: those whose response failed with a dead-token code. */
function deadTokens(tokens, responses) {
  return tokens.filter((_, i) => {
    const r = responses[i];
    return r && !r.success && r.error && DEAD_TOKEN_CODES.has(r.error.code);
  });
}

module.exports = { storagePathsOfPosts, invitationMessage, deadTokens };
